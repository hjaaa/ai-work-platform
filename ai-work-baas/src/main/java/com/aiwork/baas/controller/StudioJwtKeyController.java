package com.aiwork.baas.controller;

import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.security.CurrentUserProvider;
import com.aiwork.baas.service.JwtKeyRotationService;
import com.aiwork.baas.service.ProjectAccessService;
import com.aiwork.common.core.util.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Studio JWT 密钥轮换接口(spec §7.3),带项目归属校验。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@RestController
@RequestMapping("/studio/projects")
@RequiredArgsConstructor
public class StudioJwtKeyController {

    private final ProjectAccessService accessService;

    private final JwtKeyRotationService rotationService;

    private final CurrentUserProvider userProvider;

    @PostMapping("/{ref}/jwt-keys/rotate")
    public R<JwtKeyRotationService.RotatedKey> rotate(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(rotationService.rotate(project, userProvider.currentUserId()));
    }

    @PostMapping("/{ref}/jwt-keys/emergency-rotate")
    public R<JwtKeyRotationService.RotatedKey> emergencyRotate(@PathVariable("ref") String projectRef) {
        BaasProject project = accessService.requireOwned(projectRef);
        return R.ok(rotationService.emergencyRotate(project, userProvider.currentUserId()));
    }

}
