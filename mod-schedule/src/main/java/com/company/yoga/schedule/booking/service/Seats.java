package com.company.yoga.schedule.booking.service;

import com.company.yoga.common.exception.BusinessException;
import com.company.yoga.schedule.ScheduleResultCodes;
import com.company.yoga.schedule.booking.entity.ClassScheduleEntity;

/** Seat types of a class (V19): hybrid classes have studio seats and online seats; other classes one kind. */
final class Seats {

    static final String IN_PERSON = "IN_PERSON";
    static final String ONLINE = "ONLINE";

    private Seats() {}

    /** The seat a booking or waitlist entry takes: chosen for hybrid classes, implied by the class otherwise. */
    static String resolve(ClassScheduleEntity schedule, String requested) {
        String mode = schedule.getDeliveryMode();
        if ("HYBRID".equals(mode)) {
            if (!IN_PERSON.equals(requested) && !ONLINE.equals(requested)) throw new BusinessException(ScheduleResultCodes.HYBRID_SEAT_REQUIRED);
            return requested;
        }
        return ONLINE.equals(mode) ? ONLINE : IN_PERSON;
    }

    static boolean hasFreeSeat(ClassScheduleEntity schedule, String seat, Integer booked, Integer onlineBooked) {
        if ("HYBRID".equals(schedule.getDeliveryMode()) && ONLINE.equals(seat)) {
            int capacity = schedule.getOnlineCapacity() == null ? 0 : schedule.getOnlineCapacity();
            return (onlineBooked == null ? 0 : onlineBooked) < capacity;
        }
        return (booked == null ? 0 : booked) < schedule.getMaxCapacity();
    }

    /** Studio seats (and seats of plain online classes, as before) are numbered mats. */
    static boolean usesMat(ClassScheduleEntity schedule, String seat) {
        return !("HYBRID".equals(schedule.getDeliveryMode()) && ONLINE.equals(seat));
    }
}
