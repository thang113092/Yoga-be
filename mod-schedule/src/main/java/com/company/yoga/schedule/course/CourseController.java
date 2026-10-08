package com.company.yoga.schedule.course;

import com.company.yoga.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CourseController {
    private final CourseService service;
    @GetMapping("/courses") public ApiResponse<?> courses() { return ApiResponse.success(service.courses()); }
    @PostMapping("/courses") public ApiResponse<?> createCourse(@Valid @RequestBody CourseDto.CreateCourse r) { return ApiResponse.success(service.createCourse(r)); }
    @GetMapping("/course-classes") public ApiResponse<?> classes(@RequestParam(required=false) UUID branchId) { return ApiResponse.success(service.classes(branchId)); }
    @PostMapping("/course-classes/preview") public ApiResponse<?> preview(@Valid @RequestBody CourseDto.CreateClass r) { return ApiResponse.success(service.preview(r)); }
    @PostMapping("/course-classes") public ApiResponse<?> createClass(@Valid @RequestBody CourseDto.CreateClass r) { return ApiResponse.success(service.createClass(r)); }
    @GetMapping("/course-classes/{id}") public ApiResponse<?> detail(@PathVariable UUID id) { return ApiResponse.success(service.detail(id)); }
    @PostMapping("/course-classes/{id}/enrollments") public ApiResponse<?> enroll(@PathVariable UUID id,@Valid @RequestBody CourseDto.Enroll r) { return ApiResponse.success(service.enroll(id,r.studentId())); }
    @GetMapping("/course-classes/{id}/enrollments") public ApiResponse<?> roster(@PathVariable UUID id) { return ApiResponse.success(service.roster(id)); }
    @GetMapping("/me/course-enrollments") public ApiResponse<?> mine() { return ApiResponse.success(service.mine()); }
    @GetMapping("/course-enrollments/{id}") public ApiResponse<?> enrollment(@PathVariable UUID id) { return ApiResponse.success(service.enrollment(id)); }
    @PutMapping("/course-classes/{classId}/sessions/{id}") public ApiResponse<?> change(@PathVariable UUID classId,@PathVariable UUID id,@Valid @RequestBody CourseDto.ChangeSession r) { service.changeSession(classId,id,r); return ApiResponse.success(null); }
}
