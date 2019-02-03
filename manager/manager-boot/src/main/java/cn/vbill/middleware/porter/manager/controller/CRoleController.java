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

package cn.vbill.middleware.porter.manager.controller;

import cn.vbill.middleware.porter.manager.core.entity.CRole;
import cn.vbill.middleware.porter.manager.service.CRoleService;
import cn.vbill.middleware.porter.manager.web.message.ResponseMessage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.vbill.middleware.porter.manager.web.message.ResponseMessage.ok;

/**
 * 角色管理
 *
 * @author: fuzizheng
 * @date: 2018年04月06日 10:09
 * @version: V1.0
 * @review: fuzizheng/2018年04月06日 10:09
 */
@Api(description = "角色管理")
@RestController
@RequestMapping("/manager/crole")
public class CRoleController {

    @Autowired
    private CRoleService cRoleService;

    /**
     * 用户新增 角色下拉接口
     *
     * @author FuZizheng
     * @date 2018/4/16 上午10:23
     * @param: []
     * @return: ResponseMessage
     */
    @GetMapping
    @ApiOperation(value = "角色列表接口", notes = "角色列表接口")
    public ResponseMessage findAList() {
        List<CRole> roles = cRoleService.findList();
        return ok(roles);
    }

    /**
     * 查询所有权限
     *
     * @author he_xin
     * @return
     */
    @GetMapping("/getAll")
    @ApiOperation(value = "拿到所有的权限", notes = "拿到所有的权限")
    public ResponseMessage findAll() {
        List<CRole> roleList = cRoleService.getAll();
        return ok(roleList);
    }
}
