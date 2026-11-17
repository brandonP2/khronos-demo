package com.moga.khronosdemo.entities.enums;

// How a day went for one worker in the daily report. The first that applies wins, in this order.
public enum WorkDayStatus {
    // An entry of the day is still open
    OPEN,
    // A shift of the day has ended and there is no entry
    MISSED,
    // There is a shift that has not ended yet and no entry
    UPCOMING,
    // There are entries but no shift
    UNPLANNED,
    // An entry matched to a shift clocked in later than the late threshold
    LATE,
    OK
}
