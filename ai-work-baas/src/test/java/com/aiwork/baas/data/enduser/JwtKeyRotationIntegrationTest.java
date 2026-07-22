package com.aiwork.baas.data.enduser;

import com.aiwork.baas.data.auth.BaasJwtVerifier;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.entity.BaasJwtKey;
import com.aiwork.baas.entity.enums.JwtKeyStatus;
import com.aiwork.baas.exception.DdlConflictException;
import com.aiwork.baas.service.JwtKeyRotationService;
import com.aiwork.baas.support.DataPlaneIntegrationTestSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpMethod;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 轮换(spec §6.1/§14):previous 存续验签、未过期 previous 拒绝常规轮换、
 * 紧急轮换立即 401 且 refresh 仍可换新、并发轮换不产生双 CURRENT。
 */
class JwtKeyRotationIntegrationTest extends DataPlaneIntegrationTestSupport {

    @Autowired
    JwtKeyRotationService rotationService;

    @Autowired
    BaasJwtVerifier verifier;

    private String authUrl(String path) {
        return "http://localhost:" + port + "/data/" + fixture.project().getProjectRef() + "/auth/v1" + path;
    }

    private long currentKeyCount() {
        return jwtKeyMapper.selectCount(Wrappers.<BaasJwtKey>lambdaQuery()
            .eq(BaasJwtKey::getProjectId, fixture.project().getId())
            .eq(BaasJwtKey::getStatus, JwtKeyStatus.CURRENT));
    }

    @Test
    void rotateKeepsPreviousValidAndCreatesNewCurrent() {
        String oldKid = currentJwtKey().getKid();
        String oldToken = mintJwt(1L, null);
        JwtKeyRotationService.RotatedKey rotated = rotationService.rotate(fixture.project(), 1L);
        assertThat(rotated.kid()).isNotEqualTo(oldKid);
        assertThat(currentKeyCount()).isEqualTo(1L);
        // previous 未过 valid_until:旧 kid 签发的 JWT 仍验签通过
        assertThat(verifier.verify(oldToken, fixture.project()).userId()).isEqualTo(1L);
    }

    @Test
    void rotateWithUnexpiredPreviousIs409() {
        rotationService.rotate(fixture.project(), 1L);
        assertThatThrownBy(() -> rotationService.rotate(fixture.project(), 1L))
            .isInstanceOf(DdlConflictException.class);
    }

    /** §14 既有场景:紧急轮换后,原 current 与 previous 签发的 JWT 均立即 401,refresh 仍可换新。 */
    @Test
    void emergencyRotateKillsCurrentAndPreviousJwtButRefreshSurvives() {
        JsonNode session = json(call(HttpMethod.POST, authUrl("/signup"),
                headers(fixture.publishableKey(), null),
                "{\"email\":\"emer@example.com\",\"password\":\"password-ok\"}"));
        String previousSignedAccess = session.get("access_token").textValue();
        // 先常规轮换:原 current 降级 previous;再经 refresh 取得新 current 签发的 access
        rotationService.rotate(fixture.project(), 1L);
        JsonNode rotatedSession = json(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + session.get("refresh_token").textValue() + "\"}"));
        String currentSignedAccess = rotatedSession.get("access_token").textValue();
        String latestRefreshToken = rotatedSession.get("refresh_token").textValue();

        rotationService.emergencyRotate(fixture.project(), 1L);

        // current 与 previous 签发的 JWT 均立即 401(密钥无缓存直查)
        assertThatThrownBy(() -> verifier.verify(previousSignedAccess, fixture.project()))
            .isInstanceOf(DataApiException.class);
        assertThatThrownBy(() -> verifier.verify(currentSignedAccess, fixture.project()))
            .isInstanceOf(DataApiException.class);
        // 会话与 refresh token 不撤销:换取新 key 签发的 access JWT
        JsonNode refreshed = json(call(HttpMethod.POST, authUrl("/token?grant_type=refresh_token"),
                headers(fixture.publishableKey(), null),
                "{\"refresh_token\":\"" + latestRefreshToken + "\"}"));
        assertThat(verifier.verify(refreshed.get("access_token").textValue(), fixture.project()).userId())
            .isEqualTo(session.get("user").get("id").longValue());
    }

    @Test
    void concurrentRotateNeverProducesTwoCurrent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger conflicts = new AtomicInteger();
            Runnable work = () -> {
                try {
                    rotationService.rotate(fixture.project(), 1L);
                }
                catch (DdlConflictException conflict) {
                    conflicts.incrementAndGet();
                }
                // 高并发 DB 争用下,轮换失败者也可能表现为 InnoDB 死锁(事务被回滚),
                // 而非干净的 409 冲突;两者都满足"未产生双 CURRENT"的安全不变式,同样计入败者。
                catch (TransientDataAccessException deadlock) {
                    conflicts.incrementAndGet();
                }
            };
            Future<?> one = pool.submit(() -> {
                start.await();
                work.run();
                return null;
            });
            Future<?> two = pool.submit(() -> {
                start.await();
                work.run();
                return null;
            });
            start.countDown();
            one.get();
            two.get();
            assertThat(conflicts.get()).isEqualTo(1);
            assertThat(currentKeyCount()).isEqualTo(1L);
        }
        finally {
            pool.shutdownNow();
        }
    }

}
