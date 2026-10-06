package com.company.yoga.branch;

import com.company.yoga.common.api.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BranchResultCodes implements ErrorCode {

    BRANCH_NOT_FOUND("BRANCH_404", 404, "Không tìm thấy thông tin chi nhánh", "branch.not-found"),
    BRANCH_CODE_EXISTS("BRANCH_409", 409, "Mã chi nhánh đã tồn tại trong hệ thống", "branch.code-exists"),
    ROOM_NOT_FOUND("ROOM_404", 404, "Không tìm thấy phòng tập", "room.not-found"),
    ROOM_NAME_EXISTS("ROOM_409", 409, "Tên phòng tập đã tồn tại trong cơ sở này", "room.name-exists"),
    ROOM_CAPACITY_EXCEEDED("ROOM_422", 422, "Sức chứa phòng không hợp lệ", "room.invalid-capacity");

    private final String code;
    private final int httpStatusCode;
    private final String message;
    private final String key;
}
