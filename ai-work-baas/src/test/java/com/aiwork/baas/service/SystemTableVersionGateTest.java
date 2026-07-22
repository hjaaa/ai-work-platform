package com.aiwork.baas.service;

import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.provision.SystemTableManifest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class SystemTableVersionGateTest {

    private final SystemTableVersionGate gate = new SystemTableVersionGate();

    private static BaasProject project(ProjectStatus status, Integer version) {
        BaasProject project = new BaasProject();
        project.setStatus(status);
        project.setSystemTableVersion(version);
        return project;
    }

    @Test
    void activeCurrentVersionPasses() {
        assertThatCode(() -> gate.assertAuthReady(
                project(ProjectStatus.ACTIVE, SystemTableManifest.CURRENT_VERSION))).doesNotThrowAnyException();
        assertThatCode(() -> gate.assertStudioReady(
                project(ProjectStatus.ACTIVE, SystemTableManifest.CURRENT_VERSION))).doesNotThrowAnyException();
    }

    @Test
    void staleVersionFailsClosed() {
        assertThatThrownBy(() -> gate.assertAuthReady(project(ProjectStatus.ACTIVE, 0)))
            .isInstanceOfSatisfying(DataApiException.class,
                    exception -> assertThat(exception.status()).isEqualTo(403));
        assertThatThrownBy(() -> gate.assertStudioReady(project(ProjectStatus.ACTIVE, 0)))
            .isInstanceOf(DdlConflictException.class);
        assertThatThrownBy(() -> gate.assertAuthReady(project(ProjectStatus.ACTIVE, null)))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> gate.assertStudioReady(project(ProjectStatus.ACTIVE, null)))
            .isInstanceOf(DdlConflictException.class);
    }

    @Test
    void nonActiveStatusesFailClosed() {
        for (ProjectStatus status : new ProjectStatus[] { ProjectStatus.PROVISIONING, ProjectStatus.MIGRATING,
                ProjectStatus.FAILED, ProjectStatus.DELETING, ProjectStatus.DELETED }) {
            assertThatThrownBy(() -> gate.assertAuthReady(
                    project(status, SystemTableManifest.CURRENT_VERSION))).isInstanceOf(DataApiException.class);
            assertThatThrownBy(() -> gate.assertStudioReady(
                    project(status, SystemTableManifest.CURRENT_VERSION))).isInstanceOf(DdlConflictException.class);
        }
    }

    @Test
    void nullProjectFailsClosed() {
        assertThatThrownBy(() -> gate.assertAuthReady(null))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> gate.assertStudioReady(null))
            .isInstanceOf(DdlConflictException.class);
    }

}
