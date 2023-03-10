package com.sdms.service.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sdms.common.page.Page;
import com.sdms.common.page.PageRequest;
import com.sdms.common.result.OperationResult;
import com.sdms.dao.StudentDao;
import com.sdms.entity.Role;
import com.sdms.entity.Student;
import com.sdms.entity.User;
import com.sdms.qmodel.QRoom;
import com.sdms.qmodel.QStudent;
import com.sdms.qmodel.QTeachingClass;
import com.sdms.qmodel.QUser;
import com.sdms.service.StudentService;
import com.sdms.service.UserService;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.List;

import static com.sdms.common.result.OperationResult.failure;
import static com.sdms.common.result.OperationResult.success;
import static com.sdms.common.util.StringUtils.*;

@Slf4j
@Service
public class StudentServiceImpl implements StudentService {

    @Resource
    private StudentDao studentDao;

    @Resource
    private UserService userService;

    @Resource
    private JPAQueryFactory queryFactory;

    @Override
    public void fillTransientFields(Student student) {
        val user = student.getUser();
        if (user != null) {
            student.setUserId(user.getId());
            student.setUsername(user.getUsername());
            student.setPhone(user.getPhone());
        }
        val room = student.getRoom();
        if (room != null) {
            student.setRoomId(room.getId());
            student.setRoomName(room.getName());
        }
        val teachingClass = student.getTeachingClass();
        if (teachingClass != null) {
            student.setTeachingClassId(teachingClass.getId());
            student.setTeachingClassName(teachingClass.getName());
        }
    }

    @Override
    public Page<Student> fetchPage(PageRequest request) {
        boolean vague = request.getQueryType() == 0; // 0 ??????????????????
        val param = request.getParam();
        val studentId = trimAllWhitespace(param.get("studentId"));
        val teachingClassId = parseLong(param.get("teachingClassId"));
        val keyWord = trimAllWhitespace(param.get("keyWord"));
        val student = QStudent.student;
        val teachingClass = QTeachingClass.teachingClass;
        val user = QUser.user;
        // ????????????????????????
        val condition = new BooleanBuilder();
        if (teachingClassId != null) {
            condition.and(teachingClass.id.eq(teachingClassId));
        }
        val subCondition = new BooleanBuilder();
        if (vague) {
            // ????????????
            if (!isBlankOrNull(keyWord)) {
                subCondition.or(student.name.like("%" + keyWord + "%"));
            }
            if (!isBlankOrNull(studentId)) {
                subCondition.or(student.id.like("%" + studentId + "%"));
            }
        } else {
            // ????????????
            if (!isBlankOrNull(keyWord)) {
                subCondition.or(student.name.eq(keyWord));
            }
            if (!isBlankOrNull(studentId)) {
                subCondition.or(student.id.eq(studentId));
            }
        }
        condition.and(subCondition);
        val room = QRoom.room;
        val query = queryFactory
                .select(Projections.bean(Student.class,
                        student.id,
                        student.name,
                        room.id.as("roomId"),
                        room.name.as("roomName"),
                        user.username.as("username"),
                        teachingClass.id.as("teachingClassId"),
                        teachingClass.name.as("teachingClassName")))
                .from(student)
                .leftJoin(room).on(student.room.id.eq(room.id))
                .leftJoin(teachingClass).on(student.teachingClass.id.eq(teachingClass.id))
                .leftJoin(user).on(student.user.id.eq(user.id))
                .orderBy(student.id.asc())
                .where(condition)
                .offset((request.getPage() - 1) * request.getLimit()).limit(request.getLimit());
        // ??????????????????
        val result = query.fetchResults();
        result.getResults().forEach(this::fillTransientFields);
        return Page.of(result);
    }

    @Override
    public Student getStudentById(String id) {
        val optional = studentDao.findById(id);
        if (optional.isPresent()) {
            val student = optional.get();
            this.fillTransientFields(student);
            return student;
        }
        return null;
    }

    @Override
    public OperationResult<Student> saveStudent(Student student) {
        if (student == null || isBlankOrNull(student.getId())) {
            return failure("?????????null");
        }
        val name = student.getName();
        if (isBlankOrNull(name)) {
            return failure("??????????????????????????????????????????");
        }
        if (studentDao.existsById(student.getId())) {
            // ??????
            return success(studentDao.save(student));
        } else {
            // ??????,?????????????????????????????????????????????????????????
            val user = new User();
            user.setUsername(student.getId());
            user.setPassword(student.getId());
            val role = new Role();
            role.setId(1L); // 1????????????
            user.setRole(role);
            val result = userService.saveUser(user);
            if (result.isSuccess()) {
                student.setUser(result.getValue());
                return success(studentDao.save(student));
            } else {
                return failure("??????????????????");
            }
        }
    }

    @Override
    public OperationResult<String> deleteStudentByIds(Collection<String> ids) {
        try {
            studentDao.deleteStudentsByIdIn(ids);
        } catch (Exception e) {
            log.error("??????????????????,ids=" + ids + ",error is " + e.getMessage());
            return failure("????????????");
        }
        return success("????????????");
    }

    @Override
    public List<Student> listAllStudents() {
        return studentDao.findAll();
    }
}
