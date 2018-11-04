package org.kissweb.rest;

import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.kissweb.DateUtils;
import org.kissweb.FileUtils;

import javax.servlet.ServletContext;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.beans.PropertyVetoException;
import java.io.*;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.stream.Collectors;


/**
 * Author: Blake McBride
 * Date: 5/4/18
 */
@WebServlet("/rest")
@MultipartConfig
public class MainServlet extends ServiceBase {

    private static final Logger logger = Logger.getLogger(MainServlet.class);

    static final int MaxHold = 600;         // number of seconds to cache microservices before unloading them
    static final int CheckCacheDelay = 60;  // how often to check to unload microservices in seconds
    private static boolean systemInitialized = false;
    private ServletContext servletContext;
    private HttpServletRequest request;

    enum ExecutionReturn {
        Success,
        NotFound,
        Error
    }

    private void initializeSystem(HttpServletRequest request, HttpServletResponse response) throws ClassNotFoundException, PropertyVetoException, SQLException {
        setApplicationPath(request);
        ExecutionReturn res = (new GroovyService()).internalGroovy(this, response, null, "KissInit", "init");
        if (res == ExecutionReturn.Success) {
            String database = getDatabase();
            hasDatabase = database != null  &&  !database.isEmpty();
            if (hasDatabase)
                makeDatabaseConnection();
            systemInitialized = true;
        }
    }

    /**
     * Get the absolute path of the root of the back-end application.
     *
     * @return
     */
    public String getRealPath() {
        return servletContext.getRealPath("/");
    }

    /**
     * Returns the ServletContext.
     *
     * @return
     */
    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    /**
     * Returns the HttpServletRequest.
     *
     * @return
     */
    public HttpServletRequest getRequest() {
        return request;
    }

    /**
     * Return the number of files being uploaded.
     *
     * @return
     *
     * @see #getUploadFileName(int)
     * @see #getUploadBufferedInputStream(int)
     * @see #saveUploadFile(int)
     */
    public int getUploadFileCount() {
        int i = 0;
        for ( ; true ; i++) {
            Part filePart = null;
            try {
                filePart = request.getPart("file-" + i);
            } catch (Exception e) {
            }
            if (filePart == null)
                break;
        }
        return i;
    }

    private String getFileName(final Part part) {
        final String partHeader = part.getHeader("content-disposition");
        for (String content : part.getHeader("content-disposition").split(";")) {
            if (content.trim().startsWith("filename")) {
                return content.substring(
                        content.indexOf('=') + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    /**
     * Returns the name of the file being uploaded.
     *
     * @param i beginning at 0
     * @return
     *
     * @see #getUploadFileCount()
     * @see #getUploadBufferedInputStream(int)
     * @see #saveUploadFile(int)
     */
    public String getUploadFileName(int i) {
        try {
            Part filePart = request.getPart("file-" + i);
            return getFileName(filePart);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * In file upload scenarios, this method returns a BufferedInputStream
     * associated with file number i.  When done, the stream must be
     * closed by the application.
     *
     * @param i starting from 0
     * @return
     *
     * @see BufferedInputStream#close()
     * @see #getUploadFileCount()
     * @see #getUploadFileName(int)
     * @see #saveUploadFile(int)
     */
    public BufferedInputStream getUploadBufferedInputStream(int i) {
        try {
            Part filePart = request.getPart("file-" + i);
            return new BufferedInputStream(filePart.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads upload file "n", saves it to a temporary file, and returns the path to that file.
     *
     * @param n
     * @return
     * @throws IOException
     *
     * @see #getUploadFileName(int)
     * @see #getUploadBufferedInputStream(int)
     */
    public String saveUploadFile(int n) throws IOException {
        File f = FileUtils.createTempFile("save", "tmp");
        try (
                BufferedInputStream bis = getUploadBufferedInputStream(n);
                BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(f));
        ) {
            int c;
            while (-1 != (c = bis.read()))
                bos.write(c);
        }
        return f.getAbsolutePath();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        this.request = request;
        servletContext = request.getServletContext();
        String _className;
        String _method;
        JSONObject injson;
        JSONObject outjson = new JSONObject();
        ExecutionReturn res;

        if (!systemInitialized) {
            try {
                initializeSystem(request, response);
                if (!systemInitialized)
                    return;
            } catch (Exception e) {
                return;
            }
        }

        try {
            newDatabaseConnection();
        } catch (Throwable e) {
            if (e instanceof Exception)
                errorReturn(response, "Unable to connect to the database", (Exception) e);
            else
                errorReturn(response, "Unable to connect to the database", null);
            return;
        }

        _className = request.getParameter("_class");
        if (_className != null) {
            //  is file upload
            _method = request.getParameter("_method");
            if (debug)
                System.err.println("Enter back-end seeking UPLOAD service " + _className + "." + _method + "()");
            injson = new JSONObject();
            Enumeration<String> names = request.getParameterNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                String value = request.getParameter(name);
                injson.put(name, value);
            }
        } else {
            String instr = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            injson = new JSONObject(instr);
            _className = injson.getString("_class");
            _method = injson.getString("_method");
            if (debug)
                System.err.println("Enter back-end seeking REST service " + _className + "." + _method + "()");
        }

        if (_className.isEmpty())
            if (_method.equals("LoginRequired")) {
                if (debug)
                    System.err.println("Login is " + (hasDatabase ? "" : "not ") + "required");
                outjson.put("LoginRequired", hasDatabase);
                successReturn(response, outjson);
                return;
            } else if (_method.equals("Login")) {
                if (debug)
                    System.err.println("Attempting user login for " + injson.getString("username"));
                try {
                    String uuid = login(injson.getString("username"), injson.getString("password"));
                    outjson.put("uuid", uuid);
                    successReturn(response, outjson);
                    if (debug)
                        System.err.println("Login successful");
                    return;
                } catch (Exception e) {
                    errorReturn(response, "Login failure", e);
                    if (debug)
                        System.err.println("Login failure");
                    return;
                }
            } else if (hasDatabase) {
            try {
                if (debug)
                    System.err.println("Validating uuid " + injson.getString("_uuid"));
                checkLogin(injson.getString("_uuid"));
            } catch (Exception e) {
                if (debug)
                    System.err.println("Login failure");
                errorReturn(response, "Login failure", e);
                return;
            }
            if (debug)
                System.err.println("Login success");
        }
         res = (new GroovyService()).tryGroovy(this, response, _className, _method, injson, outjson);
        if (res == ExecutionReturn.Error)
            return;

        if (res == ExecutionReturn.NotFound) {
            res = (new JavaService()).tryJava(this, response, _className, _method, injson, outjson);
            if (res == ExecutionReturn.Error)
                return;
        }

        if (res == ExecutionReturn.NotFound) {
            res = (new LispService()).tryLisp(this, response, _className, _method, injson, outjson);
            if (res == ExecutionReturn.Error)
                return;
        }
        if (res == ExecutionReturn.NotFound) {
            res = (new CompiledJavaService()).tryCompiledJava(this, response, _className, _method, injson, outjson);
            if (res == ExecutionReturn.Error)
                return;
        }

        if (res == ExecutionReturn.NotFound) {
            if (debug)
                System.err.println("No back-end code found for " + _className);
            errorReturn(response, "No back-end code found for " + _className, null);
        } else {
            if (debug)
                System.err.println("REST service " + _className + "." + _method + "() executed successfully");
            successReturn(response, outjson);
        }
    }

    private void successReturn(HttpServletResponse response, JSONObject outjson) throws IOException {
        try {
            if (DB != null)
                DB.commit();
        } catch (SQLException e) {

        }
        outjson.put("_Success", true);
        PrintWriter out = response.getWriter();
        out.print(outjson);
        out.flush();
        response.setContentType("application/json");
        response.setStatus(200);
        closeSession();
    }

    void errorReturn(HttpServletResponse response, String msg, Exception e) {
        if (DB != null) {
            try {
                DB.rollback();
            } catch (SQLException e1) {
            }
        }
        closeSession();
        JSONObject outjson = new JSONObject();
        outjson.put("_Success", false);
        String finalMsg = msg;
        if (e != null) {
            String m = e.getMessage();
            if (m != null)
                finalMsg = msg + " " + m;
            Throwable cause = e.getCause();
            if (cause != null) {
                m = cause.getMessage();
                if (m != null)
                    finalMsg = msg + " " + m;
            }
        }
        outjson.put("_ErrorMessage", finalMsg);
        PrintWriter out = null;
        log_error(finalMsg, e);
        try {
            out = response.getWriter();
            out.print(outjson);
            out.flush();
        } catch (IOException e1) {
            // ignore
        }
        response.setContentType("application/json");
        response.setStatus(200);
    }

    boolean isEmpty(final String str) {
        return (str == null || str.equals(""));
    }

    private void log_error(final String str, final Throwable e) {
        String time = DateUtils.todayDate() + " ";
        logger.error(time + str, e);
        e.printStackTrace();
        final StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        logger.error(sw.toString());
    }

}
