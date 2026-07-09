package com.aiwork.admin.service.impl;

import cn.hutool.core.lang.tree.Tree;
import com.aiwork.admin.api.entity.SysMenu;
import com.aiwork.admin.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SysMenuServiceImplTest {

	@Test
	void filterMenuKeepsDatabaseMenuName() {
		SysMenuServiceImpl sysMenuService = new SysMenuServiceImpl(mock(SysRoleMenuMapper.class));

		SysMenu userMenu = new SysMenu();
		userMenu.setMenuId(1100L);
		userMenu.setParentId(-1L);
		userMenu.setName("用户管理");
		userMenu.setPath("/admin/system/user/index");
		userMenu.setVisible("1");
		userMenu.setKeepAlive("0");
		userMenu.setEmbedded("0");
		userMenu.setMenuType("0");
		userMenu.setSortOrder(1);

		Set<SysMenu> menus = new HashSet<>();
		menus.add(userMenu);

		List<Tree<Long>> tree = sysMenuService.filterMenu(menus, null, null);

		assertEquals("用户管理", tree.get(0).getName());
		assertEquals("用户管理", ((Map<?, ?>) tree.get(0).get("meta")).get("title"));
	}

}
