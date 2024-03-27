package cn.iocoder.yudao.module.bpm.service.task;import cn.hutool.core.collection.CollUtil;import cn.hutool.core.util.ArrayUtil;import cn.hutool.core.util.StrUtil;import cn.iocoder.yudao.framework.common.pojo.PageResult;import cn.iocoder.yudao.framework.common.util.date.DateUtils;import cn.iocoder.yudao.framework.common.util.object.PageUtils;import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCancelReqVO;import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCreateReqVO;import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstancePageReqVO;import cn.iocoder.yudao.module.bpm.convert.task.BpmProcessInstanceConvert;import cn.iocoder.yudao.module.bpm.enums.task.BpmDeleteReasonEnum;import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;import cn.iocoder.yudao.module.bpm.framework.flowable.core.candidate.strategy.BpmTaskCandidateStartUserSelectStrategy;import cn.iocoder.yudao.module.bpm.framework.flowable.core.enums.BpmConstants;import cn.iocoder.yudao.module.bpm.framework.flowable.core.event.BpmProcessInstanceEventPublisher;import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.FlowableUtils;import cn.iocoder.yudao.module.bpm.service.definition.BpmProcessDefinitionService;import cn.iocoder.yudao.module.bpm.service.message.BpmMessageService;import cn.iocoder.yudao.module.system.api.user.AdminUserApi;import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;import jakarta.annotation.Resource;import jakarta.validation.Valid;import lombok.extern.slf4j.Slf4j;import org.flowable.bpmn.model.BpmnModel;import org.flowable.bpmn.model.UserTask;import org.flowable.engine.HistoryService;import org.flowable.engine.RuntimeService;import org.flowable.engine.delegate.event.FlowableCancelledEvent;import org.flowable.engine.history.HistoricProcessInstance;import org.flowable.engine.history.HistoricProcessInstanceQuery;import org.flowable.engine.repository.ProcessDefinition;import org.flowable.engine.runtime.ProcessInstance;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.validation.annotation.Validated;import java.util.List;import java.util.Map;import java.util.Objects;import java.util.Set;import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;import static cn.iocoder.yudao.module.bpm.enums.ErrorCodeConstants.*;/** * 流程实例 Service 实现类 * * ProcessDefinition & ProcessInstance & Execution & Task 的关系： *  1. <a href="https://blog.csdn.net/bobozai86/article/details/105210414" /> * * HistoricProcessInstance & ProcessInstance 的关系： *  1. <a href=" https://my.oschina.net/843294669/blog/71902" /> * * 简单来说，前者 = 历史 + 运行中的流程实例，后者仅是运行中的流程实例 * * @author 芋道源码 */@Service@Validated@Slf4jpublic class BpmProcessInstanceServiceImpl implements BpmProcessInstanceService {    @Resource    private RuntimeService runtimeService;    @Resource    private HistoryService historyService;    @Resource    private BpmProcessDefinitionService processDefinitionService;    @Resource    private BpmMessageService messageService;    @Resource    private AdminUserApi adminUserApi;    @Resource    private BpmProcessInstanceEventPublisher processInstanceEventPublisher;    @Override    public ProcessInstance getProcessInstance(String id) {        return runtimeService.createProcessInstanceQuery()                .includeProcessVariables()                .processInstanceId(id)                .singleResult();    }    @Override    public List<ProcessInstance> getProcessInstances(Set<String> ids) {        return runtimeService.createProcessInstanceQuery().processInstanceIds(ids).list();    }    @Override    public HistoricProcessInstance getHistoricProcessInstance(String id) {        return historyService.createHistoricProcessInstanceQuery().processInstanceId(id).includeProcessVariables().singleResult();    }    @Override    public List<HistoricProcessInstance> getHistoricProcessInstances(Set<String> ids) {        return historyService.createHistoricProcessInstanceQuery().processInstanceIds(ids).list();    }    @Override    public PageResult<HistoricProcessInstance> getProcessInstancePage(Long userId,                                                                      BpmProcessInstancePageReqVO pageReqVO) {        // 通过 BpmProcessInstanceExtDO 表，先查询到对应的分页        HistoricProcessInstanceQuery processInstanceQuery = historyService.createHistoricProcessInstanceQuery()                .includeProcessVariables()                .orderByProcessInstanceStartTime().desc();        if (userId != null) { // 【我的流程】菜单时，需要传递该字段            processInstanceQuery.startedBy(String.valueOf(userId));        }  else if (pageReqVO.getStartUserId() != null) { // 【管理流程】菜单时，才会传递该字段            processInstanceQuery.startedBy(String.valueOf(pageReqVO.getStartUserId()));        }        if (StrUtil.isNotEmpty(pageReqVO.getName())) {            processInstanceQuery.processInstanceNameLike("%" + pageReqVO.getName() + "%");        }        if (StrUtil.isNotEmpty(pageReqVO.getProcessDefinitionId())) {            processInstanceQuery.processDefinitionId("%" + pageReqVO.getProcessDefinitionId() + "%");        }        if (StrUtil.isNotEmpty(pageReqVO.getCategory())) {            processInstanceQuery.processDefinitionCategory(pageReqVO.getCategory());        }        if (pageReqVO.getStatus() != null) {            processInstanceQuery.variableValueEquals(BpmConstants.PROCESS_INSTANCE_VARIABLE_STATUS, pageReqVO.getStatus());        }        if (ArrayUtil.isNotEmpty(pageReqVO.getCreateTime())) {            processInstanceQuery.startedAfter(DateUtils.of(pageReqVO.getCreateTime()[0]));            processInstanceQuery.startedBefore(DateUtils.of(pageReqVO.getCreateTime()[1]));        }        // 查询数量        long processInstanceCount = processInstanceQuery.count();        if (processInstanceCount == 0) {            return PageResult.empty(processInstanceCount);        }        // 查询列表        List<HistoricProcessInstance> processInstanceList = processInstanceQuery.listPage(PageUtils.getStart(pageReqVO), pageReqVO.getPageSize());        return new PageResult<>(processInstanceList, processInstanceCount);    }    @Override    @Transactional(rollbackFor = Exception.class)    public String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqVO createReqVO) {        // 获得流程定义        ProcessDefinition definition = processDefinitionService.getProcessDefinition(createReqVO.getProcessDefinitionId());        // 发起流程        return createProcessInstance0(userId, definition, createReqVO.getVariables(), null,                createReqVO.getStartUserSelectAssignees());    }    @Override    public String createProcessInstance(Long userId, @Valid BpmProcessInstanceCreateReqDTO createReqDTO) {        // 获得流程定义        ProcessDefinition definition = processDefinitionService.getActiveProcessDefinition(createReqDTO.getProcessDefinitionKey());        // 发起流程        return createProcessInstance0(userId, definition, createReqDTO.getVariables(), createReqDTO.getBusinessKey(),                createReqDTO.getStartUserSelectAssignees());    }    private String createProcessInstance0(Long userId, ProcessDefinition definition,                                          Map<String, Object> variables, String businessKey,                                          Map<String, List<Long>> startUserSelectAssignees) {        // 1.1 校验流程定义        if (definition == null) {            throw exception(PROCESS_DEFINITION_NOT_EXISTS);        }        if (definition.isSuspended()) {            throw exception(PROCESS_DEFINITION_IS_SUSPENDED);        }        // 1.2 校验发起人自选审批人        validateStartUserSelectAssignees(definition, startUserSelectAssignees);        // 2. 创建流程实例        FlowableUtils.filterProcessInstanceFormVariable(variables); // 过滤一下，避免 ProcessInstance 系统级的变量被占用        variables.put(BpmConstants.PROCESS_INSTANCE_VARIABLE_STATUS, // 流程实例状态：审批中                BpmProcessInstanceStatusEnum.RUNNING.getStatus());        if (CollUtil.isNotEmpty(startUserSelectAssignees)) {            variables.put(BpmConstants.PROCESS_INSTANCE_VARIABLE_START_USER_SELECT_ASSIGNEES, startUserSelectAssignees);        }        ProcessInstance instance = runtimeService.createProcessInstanceBuilder()                .processDefinitionId(definition.getId())                .businessKey(businessKey)                .name(definition.getName().trim())                .variables(variables)                .start();        return instance.getId();    }    private void validateStartUserSelectAssignees(ProcessDefinition definition, Map<String, List<Long>> startUserSelectAssignees) {        // 1. 获得发起人自选审批人的 UserTask 列表        BpmnModel bpmnModel = processDefinitionService.getProcessDefinitionBpmnModel(definition.getId());        List<UserTask> userTaskList = BpmTaskCandidateStartUserSelectStrategy.getStartUserSelectUserTaskList(bpmnModel);        if (CollUtil.isEmpty(userTaskList)) {            return;        }        // 2. 校验发起人自选审批人的 UserTask 是否都配置了        userTaskList.forEach(userTask -> {            List<Long> assignees = startUserSelectAssignees != null ? startUserSelectAssignees.get(userTask.getId()) : null;            if (CollUtil.isEmpty(assignees)) {                throw exception(PROCESS_INSTANCE_START_USER_SELECT_ASSIGNEES_NOT_CONFIG, userTask.getName());            }            Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(assignees);            assignees.forEach(assignee -> {                if (userMap.get(assignee) == null) {                    throw exception(PROCESS_INSTANCE_START_USER_SELECT_ASSIGNEES_NOT_EXISTS, userTask.getName(), assignee);                }            });        });    }    @Override    public void cancelProcessInstanceByStartUser(Long userId, @Valid BpmProcessInstanceCancelReqVO cancelReqVO) {        // 1.1 校验流程实例存在        ProcessInstance instance = getProcessInstance(cancelReqVO.getId());        if (instance == null) {            throw exception(PROCESS_INSTANCE_CANCEL_FAIL_NOT_EXISTS);        }        // 1.2 只能取消自己的        if (!Objects.equals(instance.getStartUserId(), String.valueOf(userId))) {            throw exception(PROCESS_INSTANCE_CANCEL_FAIL_NOT_SELF);        }        // 2. 通过删除流程实例，实现流程实例的取消,        // 删除流程实例，正则执行任务 ACT_RU_TASK. 任务会被删除。        deleteProcessInstance(cancelReqVO.getId(),                BpmDeleteReasonEnum.CANCEL_PROCESS_INSTANCE_BY_START_USER.format(cancelReqVO.getReason()));        // 3. 进一步的处理，交给 updateProcessInstanceCancel 方法    }    @Override    public void cancelProcessInstanceByAdmin(Long userId, BpmProcessInstanceCancelReqVO cancelReqVO) {        // 1.1 校验流程实例存在        ProcessInstance instance = getProcessInstance(cancelReqVO.getId());        if (instance == null) {            throw exception(PROCESS_INSTANCE_CANCEL_FAIL_NOT_EXISTS);        }        // 1.2 管理员取消，不用校验是否为自己的        AdminUserRespDTO user = adminUserApi.getUser(userId).getCheckedData();        // 2. 通过删除流程实例，实现流程实例的取消,        // 删除流程实例，正则执行任务 ACT_RU_TASK. 任务会被删除。        deleteProcessInstance(cancelReqVO.getId(),                BpmDeleteReasonEnum.CANCEL_PROCESS_INSTANCE_BY_ADMIN.format(user.getNickname(), cancelReqVO.getReason()));        // 3. 进一步的处理，交给 updateProcessInstanceCancel 方法    }    @Override    public void updateProcessInstanceWhenCancel(FlowableCancelledEvent event) {        // 1. 判断是否为 Reject 不通过。如果是，则不进行更新.        // 因为，updateProcessInstanceReject 方法（审批不通过），已经进行更新了        if (BpmDeleteReasonEnum.isRejectReason((String) event.getCause())) {            return;        }        // 2. 更新流程实例 status        runtimeService.setVariable(event.getProcessInstanceId(), BpmConstants.PROCESS_INSTANCE_VARIABLE_STATUS,                BpmProcessInstanceStatusEnum.CANCEL.getStatus());        // 3. 发送流程实例的状态事件        // 注意：此时如果去查询 ProcessInstance 的话，字段是不全的，所以去查询了 HistoricProcessInstance        HistoricProcessInstance processInstance = getHistoricProcessInstance(event.getProcessInstanceId());        // 发送流程实例的状态事件        processInstanceEventPublisher.sendProcessInstanceResultEvent(                BpmProcessInstanceConvert.INSTANCE.buildProcessInstanceStatusEvent(this, processInstance, BpmProcessInstanceStatusEnum.CANCEL.getStatus()));    }    @Override    public void updateProcessInstanceWhenApprove(ProcessInstance instance) {        // 1. 更新流程实例 status        runtimeService.setVariable(instance.getId(), BpmConstants.PROCESS_INSTANCE_VARIABLE_STATUS,                BpmProcessInstanceStatusEnum.APPROVE.getStatus());        // 2. 发送流程被【通过】的消息        messageService.sendMessageWhenProcessInstanceApprove(BpmProcessInstanceConvert.INSTANCE.buildProcessInstanceApproveMessage(instance));        // 3. 发送流程实例的状态事件        // 注意：此时如果去查询 ProcessInstance 的话，字段是不全的，所以去查询了 HistoricProcessInstance        HistoricProcessInstance processInstance = getHistoricProcessInstance(instance.getId());        processInstanceEventPublisher.sendProcessInstanceResultEvent(                BpmProcessInstanceConvert.INSTANCE.buildProcessInstanceStatusEvent(this, processInstance, BpmProcessInstanceStatusEnum.APPROVE.getStatus()));    }    @Override    @Transactional(rollbackFor = Exception.class)    public void updateProcessInstanceReject(String id, String reason) {        // 1. 更新流程实例 status        runtimeService.setVariable(id, BpmConstants.PROCESS_INSTANCE_VARIABLE_STATUS, BpmProcessInstanceStatusEnum.REJECT.getStatus());        // 2. 删除流程实例，以实现驳回任务时，取消整个审批流程        ProcessInstance processInstance = getProcessInstance(id);        deleteProcessInstance(id, StrUtil.format(BpmDeleteReasonEnum.REJECT_TASK.format(reason)));        // 3. 发送流程被【不通过】的消息        messageService.sendMessageWhenProcessInstanceReject(BpmProcessInstanceConvert.INSTANCE.buildProcessInstanceRejectMessage(processInstance, reason));        // 4. 发送流程实例的状态事件        processInstanceEventPublisher.sendProcessInstanceResultEvent(                BpmProcessInstanceConvert.INSTANCE.buildProcessInstanceStatusEvent(this, processInstance, BpmProcessInstanceStatusEnum.REJECT.getStatus()));    }    private void deleteProcessInstance(String id, String reason) {        runtimeService.deleteProcessInstance(id, reason);    }}