package com.aiwork.baas.controller;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.EndUserAdminService;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.common.core.util.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio 终端用户管理接口(spec §7.3):项目归属校验 + ACTIVE/v3 版本准入。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@RestController
@RequestMapping("/studio/projects")
@RequiredArgsConstructor
public class StudioEndUserController {

    private final ProjectAccessService accessService;

    private final EndUserAdminService adminService;

    private final CurrentUserProvider userProvider;

    @GetMapping("/{ref}/users")
    public R<EndUserAdminService.UserPage> list(@PathVariable("ref") String projectRef,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(adminService.list(project, page, size));
    }

    @DeleteMapping("/{ref}/users/{userId}")
    public R<Void> softDelete(@PathVariable("ref") String projectRef, @PathVariable("userId") long userId) {
        BaasProject project = accessService.requireOwned(projectRef);
        adminService.softDelete(project, userId, userProvider.currentUserId());
        return R.ok();
    }

    @PostMapping("/{ref}/users/{userId}/restore")
    public R<Void> restore(@PathVariable("ref") String projectRef, @PathVariable("userId") long userId) {
        BaasProject project = accessService.requireOwned(projectRef);
        adminService.restore(project, userId, userProvider.currentUserId());
        return R.ok();
    }

}
