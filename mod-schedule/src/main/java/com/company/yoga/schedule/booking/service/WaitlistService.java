package com.company.yoga.schedule.booking.service;

import com.company.yoga.schedule.booking.dto.WaitlistDto;
import com.company.yoga.schedule.booking.entity.BookingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistService {

    WaitlistDto.Resp joinWaitlist(WaitlistDto.JoinReq req);

    WaitlistDto.Resp cancelWaitlist(UUID waitlistId, UUID studentId);

    Optional<BookingEntity> autoPromoteTopCandidate(UUID scheduleId);

    List<WaitlistDto.StudentWaitlistResp> getStudentWaitlists(UUID studentId);

    long getWaitingCount(UUID scheduleId);
}
