/* global DateUtils, TimeUtils */

/**
* Created by Blake McBride on 6/16/18.
*/

'use strict';


/**
 * Class to deal with dates and times together.
 *
 */
class DateTimeUtils {

    /**
     * Format a Date to a string representation looking like mm/dd/yyyy hh:mm
     *
     * @param {Date} dt
     * @returns {string}
     */
    static formatDate(dt) {
        if (!dt)
            return '';
        var hours = dt.getHours();
        var sf;
        if (hours > 12)
            hours -= 12;
        if (hours >= 12)
            sf = ' PM';
        else
            sf = ' AM';
        if (!hours)
            hours = 12;
        var min = dt.getMinutes();
        if (min < 10)
            min = '0' + min.toString();
        else
            min = min.toString();
        return (dt.getMonth() + 1).toString() + '/' + dt.getDate().toString() + '/' + dt.getFullYear().toString() + ' ' + hours.toString() + ':' + min + sf;
    }

    /**
     *
     * @param {number} dt    YYYYMMDD
     * @param {number} time  HHMM
     * @returns {string}
     */
    static formatInt(dt, time) {
        if (!dt && (time === undefined || time === null || time === ''))
            return '';
        var res = DateUtils.intToStr4(dt);
        var tf = TimeUtils.format(time);
        if (res && tf)
            return res + ' ' + tf;
        else
            return res + tf;
    }
}


//# sourceURL=kiss/DateTimeUtils.js