package com.aiwork.admin.mapper;

import com.aiwork.admin.api.dto.UserDTO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysUserMapperXmlTest {

	@Test
	void getUserVoUsesUserIdConditionInsteadOfLegacyDingTalkField() {
		Configuration configuration = new Configuration();
		configuration.addMapper(SysUserMapper.class);

		try (InputStream inputStream = SysUserMapperXmlTest.class.getResourceAsStream(
				"/mapper/SysUserMapper.xml")) {
			XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(inputStream, configuration, "mapper/SysUserMapper.xml",
					configuration.getSqlFragments());
			xmlMapperBuilder.parse();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to parse SysUserMapper.xml", e);
		}

		MappedStatement mappedStatement = configuration
				.getMappedStatement("com.aiwork.admin.mapper.SysUserMapper.getUserVo");
		UserDTO query = new UserDTO();
		query.setUserId(42L);
		BoundSql boundSql = mappedStatement.getBoundSql(Map.of("query", query));
		String sql = normalizeWhitespace(boundSql.getSql());

		assertTrue(sql.contains("u.user_id = ?"));
		assertFalse(sql.contains("u.wx_ding_userid = ?"));
		assertTrue(boundSql.getParameterMappings().stream().anyMatch(mapping -> "query.userId".equals(mapping.getProperty())));
	}

	private static String normalizeWhitespace(String sql) {
		return sql.replaceAll("\\s+", " ").trim();
	}

}
