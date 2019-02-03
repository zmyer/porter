/*
 * Copyright ©2018 vbill.cn.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */
package cn.vbill.middleware.porter.manager.core.dto;

import cn.vbill.middleware.porter.manager.core.entity.CRoleMenu;

import java.util.List;

/**
 * @Description 权限菜单
 * @Date 2018/12/4 15:01
 * @author hexin[he_xin@suixingpay.com]
 */
public class CRoleMenuVo {

    private String roleCode;

    private List<CRoleMenu> cRoleMenuList;

    public CRoleMenuVo(String roleCode, List<CRoleMenu> cRoleMenuList) {
        this.roleCode = roleCode;
        this.cRoleMenuList = cRoleMenuList;
    }

    public CRoleMenuVo() {
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public List<CRoleMenu> getcRoleMenuList() {
        return cRoleMenuList;
    }

    public void setcRoleMenuList(List<CRoleMenu> cRoleMenuList) {
        this.cRoleMenuList = cRoleMenuList;
    }
}
