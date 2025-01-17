/*
 * * Copyright (c) 2022 船山信息 chuanshaninfo.com
 * OkStack is licensed under Mulan PubL v2.
 * You can use this software according to the terms and conditions of the Mulan
 * PubL v2. You may obtain a copy of Mulan PubL v2 at:
 *          http://license.coscl.org.cn/MulanPubL-2.0
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PubL v2 for more details.
 * /
 */

package org.okstar.platform.org.staff.service;

import io.quarkus.logging.Log;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.locationtech.jts.util.Assert;
import org.okstar.platform.auth.rpc.PassportRpc;
import org.okstar.platform.common.core.defined.AccountDefines;
import org.okstar.platform.common.core.defined.JobDefines;
import org.okstar.platform.common.core.utils.OkAssert;
import org.okstar.platform.common.core.utils.OkDateUtils;
import org.okstar.platform.common.core.web.page.OkPageResult;
import org.okstar.platform.common.core.web.page.OkPageable;
import org.okstar.platform.common.rpc.RpcAssert;
import org.okstar.platform.common.rpc.RpcResult;
import org.okstar.platform.org.domain.OrgPost;
import org.okstar.platform.org.domain.OrgStaff;
import org.okstar.platform.org.domain.OrgStaffPost;
import org.okstar.platform.org.mapper.OrgStaffPostMapper;
import org.okstar.platform.org.service.OrgPostService;
import org.okstar.platform.system.rpc.SysAccountRpc;
import org.okstar.platform.system.sign.SignUpForm;
import org.okstar.platform.system.sign.SignUpResult;
import org.okstar.platform.system.vo.SysAccount0;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;


/**
 * 人员服务
 */
@Transactional
@ApplicationScoped
public class OrgStaffPostServiceImpl implements OrgStaffPostService {

    @Inject
    OrgStaffPostMapper orgStaffPostMapper;
    @Inject
    OrgStaffService staffService;
    @Inject
    OrgPostService postService;

    @Inject
    @RestClient
    SysAccountRpc sysAccountRpc;

    @Inject
    @RestClient
    PassportRpc passportRpc;

    @Override
    public void save(OrgStaffPost sysDept) {
        orgStaffPostMapper.persist(sysDept);
    }

    @Override
    public List<OrgStaffPost> findAll() {
        return orgStaffPostMapper.findAll().stream().toList();
    }

    @Override
    public OkPageResult<OrgStaffPost> findPage(OkPageable pageable) {
        var all = orgStaffPostMapper.findAll();
        var query = all.page(Page.of(pageable.getPageIndex(), pageable.getPageSize()));
        return OkPageResult.build(
                query.list(),
                query.count(),
                query.pageCount());
    }

    @Override
    public OrgStaffPost get(Long id) {
        return orgStaffPostMapper.findById(id);
    }

    @Override
    public void deleteById(Long id) {
        orgStaffPostMapper.deleteById(id);
    }

    @Override
    public void delete(OrgStaffPost sysDept) {
        orgStaffPostMapper.delete(sysDept);
    }


    @Override
    public List<OrgStaffPost> findByPostIds(Set<Long> posIds) {
        return orgStaffPostMapper    //
                .list("postId in (?1)", posIds);
    }

    @Override
    public List<OrgStaffPost> findByStaffIds(Set<Long> staffIds) {
        return orgStaffPostMapper.list("staffId in (?1)", staffIds);
    }


    @Override
    public synchronized boolean leave(Long staffId) {
        OkAssert.isTrue(staffId != null && staffId > 0, "staffId is invalid");

        OrgStaff staff = staffService.get(staffId);
        OkAssert.notNull(staff, "staff is null");

        //设置离职状态
        staff.setPostStatus(JobDefines.PostStatus.left);

        //设置离职日期
        staff.setLeftDate(OkDateUtils.now());

        //删除全部岗位关联
        var staffPosts = findByStaffIds(Set.of(staff.id));
        staffPosts.forEach(sp -> {

            //清除分配标识
            OrgPost post = postService.get(sp.getPostId());
            post.setAssignFor(null);

            //删除关联
            delete(sp);
        });


        /**
         * 注销其帐号
         */
        AccountDefines.BindType emailType= AccountDefines.BindType.email;
        RpcResult<SysAccount0> bind = sysAccountRpc.findByBind(
                emailType,
                AccountDefines.DefaultISO,
                staff.getFragment().getEmail());

        SysAccount0 account0 = RpcAssert.isTrue(bind);
        if (account0 != null) {
            Log.debugf("注销帐号:%s", account0.getUsername());
            Boolean result = RpcAssert.isTrue(passportRpc.signDown(account0.getId()));
            Log.debugf("注销帐号:%s=>%s", account0.getUsername(), result);
        }

        return true;
    }

    @Override
    public synchronized boolean join(Long staffId, SortedSet<Long> postIds) {
        Log.infof("join staffId:%s postIds:%s", staffId, postIds);

        Assert.isTrue(staffId != null && staffId > 0, "参数异常！");
        Assert.isTrue(postIds != null && !postIds.isEmpty(), "参数异常！");

        OrgStaff staff = staffService.get(staffId);
        OkAssert.notNull(staff, "staff is null");

        //设置入职状态
        staff.setPostStatus(JobDefines.PostStatus.employed);
        //设置入职日期
        staff.setJoinedDate(OkDateUtils.now());
        //设置离职日期
        staff.setLeftDate(null);

        //删除多余的绑定
        List<OrgStaffPost> existed = findByStaffId(staffId);
        List<OrgStaffPost> removable = existed.stream().filter(e -> !postIds.contains(e.id)).toList();
        removable.forEach(this::delete);

        //新增绑定
        for (Long postId : postIds) {
            if (isLinked(staff.id, postId)) {
                //已经绑定的岗位
                continue;
            }

            OrgPost post = postService.get(postId);
            //设置分配标识
            post.setAssignFor(String.valueOf(staff.id));

            //保存关联
            OrgStaffPost staffPost = new OrgStaffPost();
            staffPost.setPostId(postId);
            staffPost.setStaffId(staffId);
            save(staffPost);
        }


        /**
         * 注册其帐号(邮箱号)
         */
        AccountDefines.BindType emailType= AccountDefines.BindType.email;
        String email = staff.getFragment().getEmail();
        RpcResult<SysAccount0> bind = sysAccountRpc.findByBind(
                emailType,
                AccountDefines.DefaultISO,
                email);

        SysAccount0 account0 = RpcAssert.isTrue(bind);
        if (account0 == null) {
            SignUpForm form = new SignUpForm();
            form.setPassword(AccountDefines.DefaultPWD);
            form.setIso(AccountDefines.DefaultISO);

            //设置邮箱为帐号
            form.setAccount(email);
            form.setAccountType(emailType);
            form.setFirstName(staff.getFragment().getFirstName());
            form.setLastName(staff.getFragment().getLastName());

            Log.debugf("注册帐号:%s", form);
            var result = passportRpc.signUp(form);
            SignUpResult upResult = RpcAssert.isTrue(result);
            Log.infof("signUp=>{userId: %s, username: %s}", upResult.getUserId(), upResult.getUsername());

            staffService.setAccountId(staff.id, upResult.getUserId());
        }
        return true;
    }

    private boolean isLinked(Long staffId, Long postId) {
        return orgStaffPostMapper.count("staffId = ?1 and postId = ?2", staffId, postId) > 0;
    }

    @Override
    public List<OrgStaffPost> findByStaffId(Long staffId) {
        return orgStaffPostMapper.find("staffId = ?1", staffId).stream().toList();
    }
}
