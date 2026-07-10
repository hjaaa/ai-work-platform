package com.aiwork.admin.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aiwork.admin.api.dto.FeishuDeptInfo;
import com.aiwork.admin.api.dto.FeishuUserInfo;
import com.aiwork.admin.api.dto.UserDTO;
import com.aiwork.admin.api.entity.SysDept;
import com.aiwork.admin.api.entity.SysSocialDetails;
import com.aiwork.admin.api.entity.SysUser;
import com.aiwork.admin.api.entity.SysUserSocial;
import com.aiwork.admin.mapper.SysDeptMapper;
import com.aiwork.admin.mapper.SysSocialDetailsMapper;
import com.aiwork.admin.mapper.SysUserSocialMapper;
import com.aiwork.admin.service.SysUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书 JIT 服务测试(HTTP 全部静态 mock,联调覆盖真实链路)
 *
 * @author ai-work
 * @date 2026-07-10
 */
@ExtendWith(MockitoExtension.class)
class FeishuJitServiceImplTest {

	@Mock
	private SysSocialDetailsMapper sysSocialDetailsMapper;

	@Mock
	private SysUserService sysUserService;

	@Mock
	private SysDeptMapper sysDeptMapper;

	@Mock
	private SysUserSocialMapper sysUserSocialMapper;

	@InjectMocks
	private FeishuJitServiceImpl feishuJitService;

	private SysSocialDetails feishuSocialDetails() {
		SysSocialDetails socialDetails = new SysSocialDetails();
		socialDetails.setAppId("cli_test_app");
		socialDetails.setAppSecret("secret_test_value");
		return socialDetails;
	}

	private HttpRequest tokenRequest() {
		HttpRequest tokenRequest = mock(HttpRequest.class);
		when(tokenRequest.header(eq("Content-Type"), anyString())).thenReturn(tokenRequest);
		when(tokenRequest.body(anyString())).thenReturn(tokenRequest);
		when(tokenRequest.timeout(5000)).thenReturn(tokenRequest);
		return tokenRequest;
	}

	private HttpRequest tokenRequest(String responseBody) {
		HttpRequest tokenRequest = tokenRequest();
		HttpResponse tokenResponse = mock(HttpResponse.class);
		when(tokenRequest.execute()).thenReturn(tokenResponse);
		when(tokenResponse.body()).thenReturn(responseBody);
		return tokenRequest;
	}

	private FeishuUserInfo jitUser(String mobile) {
		FeishuUserInfo info = new FeishuUserInfo();
		info.setOpenId("ou_test");
		info.setName("张三");
		info.setMobile(mobile);
		info.setTenantUserId("emp_1001");
		info.setDeptOpenIds(List.of());
		info.setDeptChain(List.of());
		return info;
	}

	@Test
	void provisionReturnsTrueWhenBindingAlreadyExists() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(new SysUserSocial());

		assertTrue(feishuJitService.provision(jitUser("13800138000")));
		verify(sysUserService, never()).saveUser(any());
		verify(sysUserSocialMapper, never()).insert(any(SysUserSocial.class));
	}

	@Test
	void provisionBindsExistingUserByPhone() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);
		SysUser existing = new SysUser();
		existing.setUserId(5L);
		when(sysUserService.getOne(any(), eq(false))).thenReturn(existing);

		assertTrue(feishuJitService.provision(jitUser("+8613800138000")));

		verify(sysUserService, never()).saveUser(any());
		ArgumentCaptor<SysUserSocial> captor = ArgumentCaptor.forClass(SysUserSocial.class);
		verify(sysUserSocialMapper).insert(captor.capture());
		assertEquals(5L, captor.getValue().getUserId());
		assertEquals("ou_test", captor.getValue().getIdentify());
		assertEquals("emp_1001", captor.getValue().getTenantUserId());
	}

	@Test
	void provisionCreatesUserWithNormalizedPhoneAsUsername() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);
		SysUser created = new SysUser();
		created.setUserId(7L);
		// 第一次按 phone 查:无;建号后按 username 查:有
		when(sysUserService.getOne(any(), eq(false))).thenReturn(null, created);
		when(sysUserService.saveUser(any(UserDTO.class))).thenReturn(Boolean.TRUE);

		assertTrue(feishuJitService.provision(jitUser("+8613800138000")));

		ArgumentCaptor<UserDTO> userCaptor = ArgumentCaptor.forClass(UserDTO.class);
		verify(sysUserService).saveUser(userCaptor.capture());
		UserDTO saved = userCaptor.getValue();
		// +86 前缀被去掉
		assertEquals("13800138000", saved.getUsername());
		assertEquals("13800138000", saved.getPhone());
		assertEquals("张三", saved.getName());
		assertEquals("张三", saved.getNickname());
		assertNotNull(saved.getPassword());
		assertTrue(saved.getPassword().length() >= 32);
		assertNull(saved.getRole());
		assertNull(saved.getDeptIds());

		ArgumentCaptor<SysUserSocial> socialCaptor = ArgumentCaptor.forClass(SysUserSocial.class);
		verify(sysUserSocialMapper).insert(socialCaptor.capture());
		assertEquals(7L, socialCaptor.getValue().getUserId());
	}

	@Test
	void provisionCreatesMissingDeptChainParentFirst() {
		when(sysUserSocialMapper.selectOne(any())).thenReturn(null);
		SysUser created = new SysUser();
		created.setUserId(7L);
		when(sysUserService.getOne(any(), eq(false))).thenReturn(null, created);
		when(sysUserService.saveUser(any(UserDTO.class))).thenReturn(Boolean.TRUE);
		// 本地均无映射
		when(sysDeptMapper.selectOne(any())).thenReturn(null);
		// 模拟 MyBatis-Plus 回填雪花 ID
		doAnswer(invocation -> {
			SysDept dept = invocation.getArgument(0);
			dept.setDeptId("od-parent".equals(dept.getFeishuDeptId()) ? 100L : 101L);
			return 1;
		}).when(sysDeptMapper).insert(any(SysDept.class));

		FeishuUserInfo info = jitUser("13800138000");
		FeishuDeptInfo parent = new FeishuDeptInfo();
		parent.setOpenDeptId("od-parent");
		parent.setName("技术中心");
		parent.setParentOpenDeptId("0");
		FeishuDeptInfo child = new FeishuDeptInfo();
		child.setOpenDeptId("od-child");
		child.setName("后端组");
		child.setParentOpenDeptId("od-parent");
		info.setDeptOpenIds(List.of("od-child"));
		info.setDeptChain(List.of(parent, child));

		assertTrue(feishuJitService.provision(info));

		ArgumentCaptor<SysDept> deptCaptor = ArgumentCaptor.forClass(SysDept.class);
		verify(sysDeptMapper, org.mockito.Mockito.times(2)).insert(deptCaptor.capture());
		List<SysDept> inserted = deptCaptor.getAllValues();
		assertEquals("od-parent", inserted.get(0).getFeishuDeptId());
		assertEquals(0L, inserted.get(0).getParentId());
		assertEquals("od-child", inserted.get(1).getFeishuDeptId());
		assertEquals(100L, inserted.get(1).getParentId());

		ArgumentCaptor<UserDTO> userCaptor = ArgumentCaptor.forClass(UserDTO.class);
		verify(sysUserService).saveUser(userCaptor.capture());
		// 用户挂到直属部门(od-child 的本地 ID)
		assertEquals(List.of(101L), userCaptor.getValue().getDeptIds());
	}

	@Test
	void fetchUserReturnsNullWhenSocialDetailsMissing() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(null);

		assertNull(feishuJitService.fetchUser("ou_test"));
	}

	@Test
	void fetchUserReturnsNullWhenTenantTokenFails() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = tokenRequest("""
			{
				"code": 10003,
				"msg": "invalid app_secret"
			}
			""");

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);

			assertNull(feishuJitService.fetchUser("ou_test"));
		}
	}

	@Test
	void fetchUserReturnsNullWhenTenantTokenRequestThrows() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = tokenRequest();
		when(tokenRequest.execute()).thenThrow(new IllegalStateException("request failed"));

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);

			assertNull(assertDoesNotThrow(() -> feishuJitService.fetchUser("ou_test")));
		}
	}

	@Test
	void fetchUserReturnsNullWhenContactRequestThrows() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = tokenRequest("""
			{
				"code": 0,
				"tenant_access_token": "t-test-token"
			}
			""");
		HttpRequest userRequest = mock(HttpRequest.class);
		when(userRequest.header(eq("Authorization"), anyString())).thenReturn(userRequest);
		when(userRequest.timeout(5000)).thenReturn(userRequest);
		when(userRequest.execute()).thenThrow(new IllegalStateException("request failed"));

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/users/")))
				.thenReturn(userRequest);

			assertNull(assertDoesNotThrow(() -> feishuJitService.fetchUser("ou_test")));
		}
	}

	@Test
	void fetchUserAppliesTimeoutToEveryFeishuRequest() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = mock(HttpRequest.class);
		HttpResponse tokenResponse = mock(HttpResponse.class);
		when(tokenRequest.header(eq("Content-Type"), anyString())).thenReturn(tokenRequest);
		when(tokenRequest.body(anyString())).thenReturn(tokenRequest);
		when(tokenRequest.timeout(5000)).thenReturn(tokenRequest);
		when(tokenRequest.execute()).thenReturn(tokenResponse);
		String tokenResponseBody = """
			{
				"code": 0,
				"tenant_access_token": "t-test-token"
			}
			""";
		when(tokenResponse.body()).thenReturn(tokenResponseBody);
		HttpRequest userRequest = mock(HttpRequest.class);
		HttpResponse userResponse = mock(HttpResponse.class);
		when(userRequest.header(eq("Authorization"), anyString())).thenReturn(userRequest);
		when(userRequest.timeout(5000)).thenReturn(userRequest);
		when(userRequest.execute()).thenReturn(userResponse);
		when(userResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": {
					"user": {
						"name": "张三",
						"mobile": "13800138000",
						"department_ids": ["od-child"]
					}
				}
			}
			""");
		HttpRequest deptRequest = mock(HttpRequest.class);
		HttpResponse deptResponse = mock(HttpResponse.class);
		when(deptRequest.header(eq("Authorization"), anyString())).thenReturn(deptRequest);
		when(deptRequest.timeout(5000)).thenReturn(deptRequest);
		when(deptRequest.execute()).thenReturn(deptResponse);
		when(deptResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": { "department": { "name": "后端组", "parent_department_id": "0" } }
			}
			""");

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/users/")))
				.thenReturn(userRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/departments/")))
				.thenReturn(deptRequest);

			assertNotNull(feishuJitService.fetchUser("ou_test"));
			assertAll(
					() -> verify(tokenRequest).timeout(5000),
					() -> verify(userRequest).timeout(5000),
					() -> verify(deptRequest).timeout(5000));
		}
	}

	@Test
	void fetchUserParsesContactFieldsAndDegradesDeptOnFailure() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = tokenRequest("""
			{
				"code": 0,
				"tenant_access_token": "t-test-token"
			}
			""");
		HttpRequest userRequest = mock(HttpRequest.class);
		HttpResponse userResponse = mock(HttpResponse.class);
		when(userRequest.header(eq("Authorization"), anyString())).thenReturn(userRequest);
		when(userRequest.timeout(5000)).thenReturn(userRequest);
		when(userRequest.execute()).thenReturn(userResponse);
		HttpRequest deptRequest = mock(HttpRequest.class);
		HttpResponse deptResponse = mock(HttpResponse.class);
		when(deptRequest.header(eq("Authorization"), anyString())).thenReturn(deptRequest);
		when(deptRequest.timeout(5000)).thenReturn(deptRequest);
		when(deptRequest.execute()).thenReturn(deptResponse);
		when(userResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": {
					"user": {
						"name": "张三",
						"mobile": "+8613800138000",
						"user_id": "emp_1001",
						"avatar": { "avatar_240": "https://example.com/a.png" },
						"department_ids": ["od-child"]
					}
				}
			}
			""");
		// 部门接口失败 → deptChain 降级为空
		when(deptResponse.body()).thenReturn("""
			{
				"code": 99991663,
				"msg": "forbidden"
			}
			""");

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/users/")))
				.thenReturn(userRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/departments/")))
				.thenReturn(deptRequest);

			FeishuUserInfo info = feishuJitService.fetchUser("ou_test");

			assertNotNull(info);
			assertEquals("张三", info.getName());
			assertEquals("+8613800138000", info.getMobile());
			assertEquals("emp_1001", info.getTenantUserId());
			assertEquals("https://example.com/a.png", info.getAvatar());
			assertEquals(1, info.getDeptOpenIds().size());
			assertTrue(info.getDeptChain().isEmpty());
		}
	}

	@Test
	void fetchUserWalksDeptChainParentFirst() {
		when(sysSocialDetailsMapper.selectOne(any())).thenReturn(feishuSocialDetails());
		HttpRequest tokenRequest = tokenRequest("""
			{
				"code": 0,
				"tenant_access_token": "t-test-token"
			}
			""");
		HttpRequest userRequest = mock(HttpRequest.class);
		HttpResponse userResponse = mock(HttpResponse.class);
		when(userRequest.header(eq("Authorization"), anyString())).thenReturn(userRequest);
		when(userRequest.timeout(5000)).thenReturn(userRequest);
		when(userRequest.execute()).thenReturn(userResponse);
		when(userResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": {
					"user": {
						"name": "张三",
						"mobile": "13800138000",
						"department_ids": ["od-child"]
					}
				}
			}
			""");
		HttpRequest childRequest = mock(HttpRequest.class);
		HttpResponse childResponse = mock(HttpResponse.class);
		when(childRequest.header(eq("Authorization"), anyString())).thenReturn(childRequest);
		when(childRequest.timeout(5000)).thenReturn(childRequest);
		when(childRequest.execute()).thenReturn(childResponse);
		when(childResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": { "department": { "name": "后端组", "parent_department_id": "od-parent" } }
			}
			""");
		HttpRequest parentRequest = mock(HttpRequest.class);
		HttpResponse parentResponse = mock(HttpResponse.class);
		when(parentRequest.header(eq("Authorization"), anyString())).thenReturn(parentRequest);
		when(parentRequest.timeout(5000)).thenReturn(parentRequest);
		when(parentRequest.execute()).thenReturn(parentResponse);
		when(parentResponse.body()).thenReturn("""
			{
				"code": 0,
				"data": { "department": { "name": "技术中心", "parent_department_id": "0" } }
			}
			""");

		try (MockedStatic<HttpRequest> mockedHttpRequest = mockStatic(HttpRequest.class)) {
			mockedHttpRequest
				.when(() -> HttpRequest.post("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
				.thenReturn(tokenRequest);
			mockedHttpRequest
				.when(() -> HttpRequest.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/users/")))
				.thenReturn(userRequest);
			mockedHttpRequest
				.when(() -> HttpRequest
					.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/departments/od-child")))
				.thenReturn(childRequest);
			mockedHttpRequest
				.when(() -> HttpRequest
					.get(startsWith("https://open.feishu.cn/open-apis/contact/v3/departments/od-parent")))
				.thenReturn(parentRequest);

			FeishuUserInfo info = feishuJitService.fetchUser("ou_test");

			assertNotNull(info);
			assertEquals(2, info.getDeptChain().size());
			// 父在前
			assertEquals("od-parent", info.getDeptChain().get(0).getOpenDeptId());
			assertEquals("技术中心", info.getDeptChain().get(0).getName());
			assertEquals("od-child", info.getDeptChain().get(1).getOpenDeptId());
			assertEquals("od-parent", info.getDeptChain().get(1).getParentOpenDeptId());
		}
	}

}
