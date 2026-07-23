/*
 *
 *      Copyright (c) 2018-2025, lengleng All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * Neither the name of the pig4cloud.com developer nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * Author: lengleng (wangiegie@gmail.com)
 *
 */

package com.aiwork.baas.exception;

import com.aiwork.common.core.util.R;
import com.aiwork.baas.ddl.lock.DdlLockInfrastructureException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Studio 管理接口异常转换。
 *
 * @author ai-work
 * @date 2026/07/17
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.aiwork.baas.controller")
public class BaasStudioExceptionHandler {

    @ExceptionHandler(ProjectNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNotFound() {
        return R.failed("项目不存在或无权访问");
    }

    @ExceptionHandler(TableNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleTableNotFound() {
        return R.failed("表不存在");
    }

    @ExceptionHandler(EndUserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleEndUserNotFound(EndUserNotFoundException exception) {
        return R.failed("终端用户不存在");
    }

    @ExceptionHandler(BaasBadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBaasBadRequest(BaasBadRequestException exception) {
        return R.failed(exception.getMessage());
    }

    @ExceptionHandler(DdlConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<Void> handleDdlConflict(DdlConflictException exception) {
        return R.failed(exception.getMessage());
    }

    @ExceptionHandler(DdlExecutionException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleDdlExecutionFailure(DdlExecutionException exception) {
        // 异常自身已按 spec §11 脱敏(固定 message + 结构化诊断码,无 cause),可整体落日志
        log.error("DDL execution failed errorCode={} sqlState={} vendorCode={}", exception.errorCode(),
                exception.sqlState(), exception.vendorCode(), exception);
        return R.failed("DDL 执行失败");
    }

    @ExceptionHandler(OwnerAclClosedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOwnerAclClosedFailure(OwnerAclClosedException exception) {
        log.error("owner column drop failed, ACL closed", exception);
        return R.failed("DDL 执行失败，owner ACL 已安全关闭");
    }

    @ExceptionHandler(DdlLockInfrastructureException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleDdlLockInfrastructureFailure(DdlLockInfrastructureException exception) {
        log.error("DDL lock infrastructure unavailable", exception);
        return R.failed("DDL 锁服务暂时不可用");
    }

    @ExceptionHandler({ BindException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(Exception exception) {
        log.warn("Studio request validation failed, type={}", exception.getClass().getSimpleName());
        return R.failed("请求参数校验失败");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.warn("Studio request parameter type mismatch, parameter={}", exception.getName());
        return R.failed("请求参数格式错误");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleUnreadableMessage() {
        return R.failed("请求体格式错误");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument() {
        return R.failed("请求参数不合法");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public R<Void> handleStateConflict() {
        return R.failed("当前项目状态不允许执行该操作");
    }

    @ExceptionHandler(ProjectProvisionException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleProvisionFailure(ProjectProvisionException exception) {
        log.error("project provisioning failed", exception);
        return R.failed("项目开通失败，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnexpected(Exception exception) {
        log.error("unexpected Studio exception", exception);
        return R.failed("服务暂时不可用，请稍后重试");
    }

}
