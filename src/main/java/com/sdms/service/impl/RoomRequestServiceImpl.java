package com.sdms.service.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sdms.common.page.Page;
import com.sdms.common.page.PageRequest;
import com.sdms.common.result.OperationResult;
import com.sdms.dao.RoomRequestDao;
import com.sdms.entity.RoomRequest;
import com.sdms.qmodel.QRoom;
import com.sdms.qmodel.QRoomRequest;
import com.sdms.qmodel.QStudent;
import com.sdms.service.RoomRequestService;
import com.sdms.service.RoomService;
import com.sdms.service.StudentService;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.sdms.common.result.OperationResult.failure;
import static com.sdms.common.result.OperationResult.success;
import static com.sdms.common.util.StringUtils.isBlankOrNull;
import static com.sdms.common.util.StringUtils.parseLong;
import static com.sdms.entity.RoomRequest.RoomRequestStatus.*;
import static com.sdms.common.util.StringUtils.trimAllWhitespace;

@Slf4j
@Service
public class RoomRequestServiceImpl implements RoomRequestService {

    @Resource
    private RoomRequestDao roomRequestDao;

    @Resource
    private StudentService studentService;

    @Resource
    private RoomService roomService;

    @Resource
    private JPAQueryFactory queryFactory;

    @Override
    public void fillTransientFields(RoomRequest roomRequest) {
        val room = roomRequest.getRoom();
        if (room != null) {
            roomRequest.setRoomId(room.getId());
            roomRequest.setRoomName(room.getName());
        }
        val status = roomRequest.getStatus();
        if (status != null) {
            roomRequest.setStatusMsg(status.getMessage());
            roomRequest.setStatusCode(status.getCode());
        }
        val student = roomRequest.getStudent();
        if (student != null) {
            roomRequest.setStudentId(student.getId());
            roomRequest.setStudentName(student.getName());
        }
    }

    @Override
    public int getNoHandleCount() {
        return roomRequestDao.countByStatusEquals(RoomRequest.RoomRequestStatus.NO_HANDLE);
    }

    @Override
    public List<RoomRequest.RoomRequestStatus> listAllStatuses() {
        return Arrays.asList(RoomRequest.RoomRequestStatus.values());
    }

    @Override
    public Page<RoomRequest> fetchPage(PageRequest request) {
        boolean vague = request.getQueryType() == 0; // 0 ??????????????????
        val param = request.getParam();
        val roomRequestId = parseLong(param.get("roomRequestId"));
        val keyWord = trimAllWhitespace(param.get("keyWord"));
        val status = parseLong(param.get("status"));
        val roomRequest = QRoomRequest.roomRequest;
        val room = QRoom.room;
        val student = QStudent.student;
        // ????????????????????????
        val condition = new BooleanBuilder();
        if (status != null) {
            val roomRequestStatus = valueOfCode(status.intValue());
            if (roomRequestStatus != null) {
                condition.and(roomRequest.status.eq(roomRequestStatus));
            }
        }
        val subCondition = new BooleanBuilder();
        if (roomRequestId != null) {
            subCondition.or(roomRequest.id.eq(roomRequestId));
        }
        if (!isBlankOrNull(keyWord)) {
            if (vague) {
                // ????????????
                subCondition.or(room.name.like("%" + keyWord + "%"));
                subCondition.or(student.name.like("%" + keyWord + "%"));
            } else {
                // ????????????
                subCondition.or(room.name.eq(keyWord));
                subCondition.or(student.name.eq(keyWord));
            }
        }
        condition.and(subCondition);
        val query = queryFactory
                .select(Projections.bean(RoomRequest.class,
                        roomRequest.id,
                        roomRequest.status,
                        room.id.as("roomId"),
                        room.name.as("roomName"),
                        student.id.as("studentId"),
                        student.name.as("studentName")))
                .from(roomRequest)
                .leftJoin(room).on(roomRequest.room.id.eq(room.id))
                .leftJoin(student).on(roomRequest.student.id.eq(student.id))
                .orderBy(roomRequest.id.asc())
                .where(condition)
                .offset((request.getPage() - 1) * request.getLimit()).limit(request.getLimit());
        // ??????????????????
        val result = query.fetchResults();
        result.getResults().forEach(this::fillTransientFields);
        return Page.of(result);
    }

    @Override
    public RoomRequest getRoomRequestById(Long id) {
        val optional = roomRequestDao.findById(id);
        if (optional.isPresent()) {
            val roomRequest = optional.get();
            this.fillTransientFields(roomRequest);
            return roomRequest;
        }
        return null;
    }

    @Override
    public OperationResult<RoomRequest> saveRoomRequest(RoomRequest roomRequest) {
        if (roomRequest == null) {
            return failure("???????????????null");
        }
        val s = roomRequest.getStudent();
        if (s == null || isBlankOrNull(s.getId())) {
            return failure("??????????????????");
        }
        val r = roomRequest.getRoom();
        if (r == null || r.getId() == null) {
            return failure("??????????????????");
        }
        roomRequest.setStatus(valueOfCode(roomRequest.getStatusCode()));
        if (roomRequest.getStatus().equals(SUCCESS)) {
            val student = studentService.getStudentById(s.getId());
            val room = roomService.getRoomById(r.getId());
            if (student != null && room != null) {
                if (roomService.countStudentsByRoomId(room.getId()) + 1 <= room.getCategory().getMaxSize()) {
                    student.setRoom(room);
                    studentService.saveStudent(student);
                } else {
                    return failure("??????????????????");
                }
            }
        } else if (roomRequest.getStatus().equals(FAILURE)) {
            val student = studentService.getStudentById(s.getId());
            if (student != null) {
                student.setRoom(null);
                studentService.saveStudent(student);
            }
        }
        return success(roomRequestDao.save(roomRequest));
    }

    @Override
    public OperationResult<String> deleteRoomRequestByIds(Collection<Long> ids) {
        try {
            roomRequestDao.deleteRoomRequestsByIdIn(ids);
        } catch (Exception e) {
            log.error("????????????????????????,ids=" + ids + ",error is " + e.getMessage());
            return failure("????????????");
        }
        return success("????????????");
    }

    @Override
    public OperationResult<RoomRequest> newRoomRequest(String studentId, Long roomId) {
        val s = studentService.getStudentById(studentId);
        val r = roomService.getRoomById(roomId);
        if (s != null && r != null) {
            val roomRequest = new RoomRequest();
            roomRequest.setRoom(r);
            roomRequest.setStudent(s);
            roomRequest.setStatus(NO_HANDLE);
            RoomRequest rq;
            try {
                rq = roomRequestDao.save(roomRequest);
            } catch (Exception e) {
                log.error("??????????????????, error=" + e.getMessage());
                return failure("??????");
            }
            return success(rq);
        } else {
            return failure("????????????");
        }
    }

}
