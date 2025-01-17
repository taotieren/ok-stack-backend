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

package org.okstar.platform.system.account.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.RandomStringUtils;
import org.okstar.platform.common.core.defined.AccountDefines;
import org.okstar.platform.common.core.exception.OkRuntimeException;
import org.okstar.platform.common.core.exception.user.OkUserException;
import org.okstar.platform.common.core.utils.OkAssert;
import org.okstar.platform.common.core.utils.OkDateUtils;
import org.okstar.platform.common.core.utils.OkMailUtil;
import org.okstar.platform.common.core.utils.OkPhoneUtils;
import org.okstar.platform.common.core.web.page.OkPageResult;
import org.okstar.platform.common.core.web.page.OkPageable;
import org.okstar.platform.common.datasource.OkAbsService;
import org.okstar.platform.common.datasource.domain.OkEntity;
import org.okstar.platform.system.account.domain.SysAccount;
import org.okstar.platform.system.account.domain.SysAccountBind;
import org.okstar.platform.system.account.domain.SysAccountPassword;
import org.okstar.platform.system.account.mapper.SysAccountBindMapper;
import org.okstar.platform.system.account.mapper.SysAccountMapper;
import org.okstar.platform.system.account.mapper.SysAccountPasswordMapper;
import org.okstar.platform.system.sign.SignUpForm;
import org.okstar.platform.system.sign.SignUpResult;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL;


/**
 * 用户 业务层处理
 */
@Transactional
@ApplicationScoped
public class SysAccountServiceImpl extends OkAbsService implements SysAccountService {

    @Inject
    SysAccountMapper sysAccountMapper;
    @Inject
    SysAccountBindMapper sysAccountBindMapper;
    @Inject
    SysAccountPasswordMapper sysAccountPasswordMapper;


    @Override
    public Optional<SysAccountPassword> lastPassword(Long accountId) {
        return sysAccountPasswordMapper.find("accountId = ?1", accountId)//
                .stream().max(Comparator.comparing(OkEntity::getCreateAt));
    }

    @Override
    public Optional<SysAccount> findByUsername(String username) {
        return sysAccountMapper.find("username = ?1", username).stream().findFirst();
    }

    @Override
    public SysAccount loadByUsername(String username) {
        Log.infof("loadByUsername:%s", username);
        Optional<SysAccount> optional = findByUsername(username);
        if (optional.isEmpty()) {
            throw new OkUserException("Can not find the user:%s.".formatted(username));
        }
        return optional.get();
    }

    /**
     * 通过用户名查询用户
     *
     * @param iso
     * @param type
     * @param value
     * @return
     */
    @Override
    public SysAccount findByBind(AccountDefines.BindType type,String iso, String value) {
        Log.infof("findByBind type:%s value:%s", type, value);

        String bindValue = value;
        if (type == AccountDefines.BindType.phone) {
            bindValue = OkPhoneUtils.canonical(value, iso);
            Log.infof("bindValue=%S", bindValue);
        }

        List<SysAccountBind> list = sysAccountBindMapper.list(
                "bindType = ?1 and bindValue = ?2",
                type,
                bindValue);
        if (list.isEmpty()) {
            return null;
        }

        SysAccountBind accountBind = list.get(0);
        Log.infof("bind=>%s", accountBind);

        return get(accountBind.getAccountId());
    }

    @Override
    public SignUpResult signUp(SignUpForm signUpForm) {
        Log.infof("signUp:%s", signUpForm);

        OkAssert.hasText(signUpForm.getIso(), "iso is empty");
        OkAssert.hasText(signUpForm.getPassword(), "password is empty");
        OkAssert.notNull(signUpForm.getAccountType(), "accountType is empty");
        OkAssert.notNull(signUpForm.getAccount(), "account is empty");

        if (signUpForm.getAccountType() == AccountDefines.BindType.phone) {
            //格式化手机号
            signUpForm.setAccount(OkPhoneUtils.canonical(signUpForm.getAccount(), signUpForm.getIso()));
        } else if (signUpForm.getAccountType() == AccountDefines.BindType.email) {
            OkAssert.isTrue(OkMailUtil.isValidEmail(signUpForm.getAccount()), "邮箱号格式不正确！");
        }

        long existed = sysAccountBindMapper.count("bindType = ?1 and bindValue = ?2",
                signUpForm.getAccountType(),
                signUpForm.getAccount());

        Log.infof("Find exist named account:%s => %s", signUpForm.getAccount(), existed);
        if (existed > 0) {
            throw new OkUserException("The account is existed");
        }

        SysAccount sysAccount = new SysAccount();
        sysAccount.setCreateAt(OkDateUtils.now());
        sysAccount.setUsername(RandomStringUtils.randomAlphanumeric(12));
        sysAccount.setIso(signUpForm.getIso());
        sysAccount.setLanguage(signUpForm.getLanguage());
        sysAccount.setAvatar(AccountDefines.DefaultAvatar);
        sysAccount.setNickname(
                Optional.ofNullable(signUpForm.getFirstName()).orElse("")
                        + Optional.ofNullable(signUpForm.getLastName()).orElse("")
        );

        sysAccountMapper.persist(sysAccount);
        Log.infof("User have been saved successfully.=>%s", sysAccount.getUsername());

        SysAccountBind bind = new SysAccountBind();
        bind.setAccountId(sysAccount.id);
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
        sysAccountBindMapper.persist(bind);

        /*
          保存密码
         */
        SysAccountPassword pwd = new SysAccountPassword();
        pwd.setAccountId(sysAccount.id);
        pwd.setCreateAt(sysAccount.getCreateAt());
        pwd.setPassword(signUpForm.getPassword());
        sysAccountPasswordMapper.persist(pwd);

        return SignUpResult.builder()
                .username(sysAccount.getUsername())
                .userId(sysAccount.id)
                .build();
    }

    @Override
    public synchronized void signDown(Long accountId) {
        SysAccount account = get(accountId);
        if (account == null) {
            //已删除
            Log.warnf("Account:%s have been deleted.", accountId);
            return;
        }

        //删除关联信息 AccountBind
        Long delete = sysAccountBindMapper.delete("accountId", accountId);
        Log.debugf("AccountBind is deleted by accountId:%s=>%s", accountId, delete);

        //删除关联信息 AccountPassword
        Long delete1 = sysAccountPasswordMapper.delete("accountId", accountId);
        Log.debugf("AccountPassword is deleted by accountId:%s=>%s", accountId, delete1);

        //删除帐号
        deleteById(account.id);
        Log.debugf("Account is deleted by id:%s", account.id);

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
        var query = all.page(Page.of(pageable.getPageIndex(), pageable.getPageSize()));
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
    public List<SysAccountBind> listBind(Long id) {
        return sysAccountBindMapper.find("accountId", id).list();
    }

    @Override
    public void setCert(Long id, String cert) {
        SysAccount account = get(id);
        account.setCert(cert);
    }
}
