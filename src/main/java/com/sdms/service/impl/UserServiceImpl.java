package com.sdms.service.impl;

import static com.sdms.common.util.BeanUtils.*;
import static com.sdms.common.util.StringUtils.*;
import static com.sdms.common.result.OperationResult.*;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sdms.common.annotation.PageRequestCheck;
import com.sdms.common.page.Page;
import com.sdms.common.page.PageRequest;
import com.sdms.common.result.OperationResult;
import com.sdms.common.util.MD5Utils;
import com.sdms.dao.UserDao;
import com.sdms.qmodel.QRole;
import com.sdms.qmodel.QUser;
import com.sdms.entity.User;
import com.sdms.service.SessionService;
import com.sdms.service.UserService;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserDao userDao;

    @Resource
    private SessionService sessionService;

    @Resource
    private JPAQueryFactory queryFactory;

    @Override
    public void fillTransientFields(User user) {
        val gender = user.getGender();
        if (gender != null) {
            user.setGenderStr(gender.getValue());
        }
        val role = user.getRole();
        if (role != null) {
            user.setRoleId(role.getId());
            user.setRoleName(role.getName());
        }
    }

    @Override
    public User getUserByUsername(String username) {
        return userDao.findByUsername(username);
    }

    @Override
    public OperationResult<User> signIn(String username, String password) {
        if (isBlankOrNull(username)) {
            return failure("????????????????????????");
        }
        if (isBlankOrNull(password)) {
            return failure("?????????????????????");
        }
        val subject = SecurityUtils.getSubject();
        try {
            log.info("??????????????????:username=" + username + ",password=" + password);
            // ????????????????????????????????????token??????,??????shiro??????????????????
            subject.login(new UsernamePasswordToken(username, password));
            // ???????????????????????? com/sdms/config/ShiroRealm.java ??? doGetAuthenticationInfo() ??????
        } catch (UnknownAccountException e) {
            return failure("??????????????????");
        } catch (IncorrectCredentialsException e) {
            return failure("????????????");
        } catch (AuthenticationException e) {
            log.error("??????,username=" + username + ",password=" + password + "," + e.getMessage());
            return failure("?????????????????????");
        }
        val user = (User) subject.getPrincipal();
        if (user == null) {
            return failure("????????????");
        }
        if (user.getRole() == null) {
            return failure("??????????????????");
        }
        sessionService.setCurrentUser(user); // ?????????????????????session???
        return success(user);
    }

    @Override
    public OperationResult<User> signOut() {
        val user = sessionService.getCurrentUser();
        if (user == null) {
            return success(new User());
        }
        try {
            sessionService.removeCurrentUser();
        } catch (Exception e) {
            log.error("??????????????????: " + e.getMessage());
            return failure("????????????????????????");
        }
        return success(user);
    }

    @Override
    public OperationResult<User> saveUser(User user) {
        if (user == null) {
            return failure("?????????null");
        }
        val username = user.getUsername();
        if (isBlankOrNull(username)) {
            return failure("????????????????????????????????????");
        }
        if (user.getId() == null && userDao.findByUsername(username) != null) {
            return failure("???????????????????????????????????????");
        }
        val rawPassword = user.getPassword();
        if (isBlankOrNull(rawPassword)) {
            return failure("????????????????????????????????????");
        }
        // ????????????
        user.setGender(User.Gender.of(user.getGenderStr()));
        // ????????????
        user.setPassword(MD5Utils.encodeWithSalt(rawPassword, username, 1));
        return success(userDao.save(user));
    }

    @Override
    public OperationResult<String> deleteUserByIds(Collection<Long> ids) {
        try {
            userDao.deleteUsersByIdIn(ids);
        } catch (Exception e) {
            log.error("??????????????????,ids=" + ids + ",error is " + e.getMessage());
            return failure("????????????");
        }
        return success("????????????");
    }

    @Override
    public OperationResult<User> updateUser(User u) {
        if (u == null || u.getId() == null || isBlankOrNull(u.getPassword())) {
            return failure("???????????????");
        }
        val user = getUserById(u.getId());
        if (user == null) {
            return failure("???????????????");
        }
        // ????????????
        u.setGender(User.Gender.of(u.getGenderStr()));
        // ????????????
        u.setPassword(MD5Utils.encodeWithSalt(u.getPassword(), user.getUsername(), 1));
        copyProperties(u, user, getNullPropertyNames(u));
        return success(userDao.save(user));
    }

    @Override
    @PageRequestCheck
    public Page<User> fetchPage(PageRequest request) {
        boolean vague = request.getQueryType() == 0; // 0 ??????????????????
        val param = request.getParam();
        val userId = parseLong(param.get("userId"));
        val roleId = parseLong(param.get("roleId"));
        val keyWord = trimAllWhitespace(param.get("keyWord"));
        val user = QUser.user;
        val role = QRole.role;
        // ????????????????????????
        val subCondition = new BooleanBuilder();
        if (userId != null) {
            subCondition.or(user.id.eq(userId));
        }
        if (!isBlankOrNull(keyWord)) {
            if (vague) {
                // ????????????
                subCondition.or(user.username.like("%" + keyWord + "%"));
                subCondition.or(user.phone.like("%" + keyWord + "%"));
                subCondition.or(user.address.like("%" + keyWord + "%"));
            } else {
                // ????????????
                subCondition.or(user.username.eq(keyWord));
                subCondition.or(user.phone.eq(keyWord));
                subCondition.or(user.address.eq(keyWord));
            }
        }
        val condition = new BooleanBuilder();
        if (roleId != null) {
            condition.and(role.id.eq(roleId));
        }
        condition.and(subCondition);
        val query = queryFactory
                .select(Projections.bean(User.class,
                        user.id,
                        user.username,
                        user.avatar,
                        user.gender,
                        user.address,
                        user.phone,
                        role.id.as("roleId"),
                        role.name.as("roleName")))
                .from(user).leftJoin(role).on(user.role.id.eq(role.id))
                .orderBy(user.id.asc())
                .where(condition)
                .offset((request.getPage() - 1) * request.getLimit()).limit(request.getLimit());
        // ??????????????????
        val result = query.fetchResults();
        result.getResults().forEach(this::fillTransientFields);
        return Page.of(result);
    }

    @Override
    public User getUserById(long id) {
        val optional = userDao.findById(id);
        if (optional.isPresent()) {
            val user = optional.get();
            this.fillTransientFields(user);
            return user;
        }
        return null;
    }
}
