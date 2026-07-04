package com.aiwork.auth.support.core;

import com.aiwork.common.core.constant.SecurityConstants;
import com.aiwork.common.security.service.AiWorkUser;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * token 输出增强
 *
 * @author lengleng
 * @date 2022/6/3
 */
public class CustomeOAuth2TokenCustomizer implements OAuth2TokenCustomizer<OAuth2TokenClaimsContext> {

	/**
	 * Customize the OAuth 2.0 Token attributes.
	 * @param context the context containing the OAuth 2.0 Token attributes
	 */
	@Override
	public void customize(OAuth2TokenClaimsContext context) {
		OAuth2TokenClaimsSet.Builder claims = context.getClaims();
		claims.claim(SecurityConstants.DETAILS_LICENSE, SecurityConstants.AIWORK_LICENSE);
		String clientId = context.getAuthorizationGrant().getName();
		claims.claim(SecurityConstants.CLIENT_ID, clientId);
		claims.claim(SecurityConstants.ACTIVE, Boolean.TRUE);

		// 客户端模式不返回具体用户信息
		if (SecurityConstants.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType().getValue())) {
			return;
		}

		AiWorkUser aiworkUser = (AiWorkUser) context.getPrincipal().getPrincipal();
		claims.claim(SecurityConstants.DETAILS_USER_ID, aiworkUser.getId().toString());
		claims.claim(SecurityConstants.DETAILS_USERNAME, aiworkUser.getUsername());
	}

}
