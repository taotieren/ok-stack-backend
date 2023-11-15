/*
 * * Copyright (c) 2022 船山科技 chuanshantech.com
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

import io.quarkus.panache.common.Page;
import org.okstar.platform.common.core.defined.JobDefines;
import org.okstar.platform.common.core.utils.OkDateUtils;
import org.okstar.platform.common.core.web.page.OkPageResult;
import org.okstar.platform.common.core.web.page.OkPageable;
import org.okstar.platform.org.domain.OrgPost;
import org.okstar.platform.org.domain.OrgStaff;
import org.okstar.platform.org.domain.OrgStaffPost;
import org.okstar.platform.org.mapper.OrgStaffPostMapper;
import org.okstar.platform.org.service.OrgPostService;
import org.springframework.util.Assert;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * 人员服务
 */
@ApplicationScoped
public class OrgStaffPostServiceImpl implements OrgStaffPostService {

    @Inject
    OrgStaffPostMapper orgStaffPostMapper;
    @Inject
    OrgStaffService staffService;
    @Inject
    OrgPostService postService;


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
        var query = all.page(Page.of(pageable.getPageNumber(), pageable.getPageSize()));
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
                .list("postId in (?1)", posIds)    //
                .stream()//
                .collect(Collectors.toList());
    }

    @Override
    public List<OrgStaffPost> findByStaffIds(Set<Long> staffIds) {
        return orgStaffPostMapper.list("staffId in (?1)", staffIds);
    }


    @Override
    public synchronized boolean leave(Long staffId) {
        Assert.isTrue(staffId != null && staffId > 0, "staffId is invalid");

        OrgStaff staff = staffService.get(staffId);
        Assert.notNull(staff, "staff is null");

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

        return true;
    }

    @Override
    public synchronized boolean join(Long staffId, Long[] postIds) {

        Assert.isTrue(staffId != null && staffId > 0, "staffId is invalid");
        Assert.isTrue(postIds != null && postIds.length > 0, "postIds is invalid");

        OrgStaff staff = staffService.get(staffId);
        Assert.notNull(staff, "staff is null");

        //设置入职状态
        staff.setPostStatus(JobDefines.PostStatus.employed);
        //设置入职日期
        staff.setJoinedDate(OkDateUtils.now());
        //设置离职日期
        staff.setLeftDate(null);

        for (Long postId : postIds) {
            OrgPost post = postService.get(postId);
            //设置分配标识
            post.setAssignFor(String.valueOf(staff.id));

            //保存关联
            OrgStaffPost staffPost = new OrgStaffPost();
            staffPost.setPostId(postId);
            staffPost.setStaffId(staffId);
            save(staffPost);
        }

        return true;
    }
}