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

package org.okstar.platform.system.account.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Page;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.okstar.platform.common.core.defined.AccountDefines;
import org.okstar.platform.common.core.exception.OkRuntimeException;
import org.okstar.platform.common.core.exception.user.OkUserException;
import org.okstar.platform.common.core.utils.OkPhoneUtils;
import org.okstar.platform.common.core.web.page.OkPageResult;
import org.okstar.platform.common.core.web.page.OkPageable;
import org.okstar.platform.common.datasource.OkAbsService;
import org.okstar.platform.system.sign.SignUpForm;
import org.okstar.platform.system.sign.SignUpResult;
import org.okstar.platform.system.account.mapper.SysAccountBindMapper;
import org.okstar.platform.system.account.mapper.SysAccountMapper;
import org.okstar.platform.system.account.domain.SysAccount;
import org.okstar.platform.system.account.domain.SysAccountBind;
import org.springframework.util.Assert;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.transaction.Transactional;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL;


/**
 * 用户 业务层处理
 */
@Slf4j
@Singleton
@Transactional
public class SysAccountServiceImpl extends OkAbsService implements SysAccountService {

    @Inject
    SysAccountMapper sysAccountMapper;
    @Inject
    SysAccountBindMapper sysAccountBindMapper;

    @Override
    public Optional<SysAccount> findByUsername(String username) {
        return sysAccountMapper.find("username = ?1", username).stream().findFirst();

    }

    /**
     * 通过用户名查询用户
     *
     * @param iso
     * @param bindType
     * @param bindValue
     * @return
     */
    @Override
    public SysAccount findByBind(String iso, AccountDefines.BindType bindType, String bindValue) {
        String bind = bindValue;
        if (bindType == AccountDefines.BindType.phone) {
            bind = OkPhoneUtils.canonical(bindValue, iso);
        }

        List<SysAccountBind> list = sysAccountBindMapper.list(
                "bindType = ?1 and bindValue = ?2",
                bindType,
                bind);
        if (list.isEmpty()) {
            return null;
        }

        return list.stream().findFirst().get().getAccount();

    }

    @Override
    public SignUpResult signUp(SignUpForm signUpForm) {
        Log.infof("signUp:%s", signUpForm);

        Assert.hasText(signUpForm.getIso(),"iso is empty");
        Assert.hasText(signUpForm.getPassword(),"password is empty");
        Assert.notNull(signUpForm.getAccountType(),"accountType is empty");
        Assert.notNull(signUpForm.getAccount(),"account is empty");

        long existed;
        if (signUpForm.getAccountType() == AccountDefines.BindType.phone) {
            signUpForm.setAccount(OkPhoneUtils.canonical(signUpForm.getAccount(), signUpForm.getIso()));
        }

        existed = sysAccountBindMapper.count("bindType = ?1 and bindValue = ?2",
                signUpForm.getAccountType(),
                signUpForm.getAccount());

        Log.infof("Find exist named account:%s => %s", signUpForm.getAccount(), existed);
        if (existed > 0) {
            throw new OkUserException("The account is existed");
        }

        SysAccount sysUser = new SysAccount();
        sysUser.setIso(signUpForm.getIso());
        sysUser.setFirstName(signUpForm.getFirstName());
        sysUser.setLastName(signUpForm.getLastName());
        sysUser.setUsername(RandomStringUtils.randomAlphanumeric(12));
        sysAccountMapper.persist(sysUser);
        Log.infof("User have been saved successfully.=>%s",
                sysUser.getUsername(), sysUser);

        SysAccountBind bind = new SysAccountBind();
        bind.setBindType(signUpForm.getAccountType());
        switch (signUpForm.getAccountType()) {
            case email -> bind.setBindValue(signUpForm.getAccount());
            case phone -> {
                try {
                    PhoneNumberUtil util = PhoneNumberUtil.getInstance();
                    Phonenumber.PhoneNumber number;
                    number = util.parse(signUpForm.getAccount(), signUpForm.getIso());
                    bind.setBindValue(util.format(number, INTERNATIONAL));
                } catch (NumberParseException e) {
                    throw new OkRuntimeException("号码无法被解析", e);
                }
            }
        }


        bind.setAccount(sysUser);
        sysAccountBindMapper.persist(bind);

        return SignUpResult.builder()
                .username(sysUser.getUsername())
                .userId(sysUser.id)
                .build();
    }

    @Override
    public List<SysAccount> findAll() {
        return sysAccountMapper.findAll().list();
    }

    @Override
    public SysAccount get(Long id) {
        return sysAccountMapper.findById(id);
    }

    @Override
    public void deleteById(Long id) {
        sysAccountMapper.deleteById(id);
    }

    @Override
    public void delete(SysAccount sysUser) {
        sysAccountMapper.delete(sysUser);
    }


    @Override
    public OkPageResult<SysAccount> findPage(OkPageable pageable) {
        var all = sysAccountMapper.findAll();
        var query = all.page(Page.of(pageable.getPageNumber(), pageable.getPageSize()));
        return OkPageResult.build(
                query.list(),
                query.count(),
                query.pageCount());
    }

    @Override
    public void save(SysAccount sysUser) {
        sysAccountMapper.persist(sysUser);
    }


    @Override
    public boolean isAdmin(Long userId) {
        return Objects.equals(1L, userId);
    }


}
