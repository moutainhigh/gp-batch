package com.hb.batch.task;

import com.hb.batch.runable.UserOrderRunnable;
import com.hb.batch.scheduler.ITaskScheduler;
import com.hb.batch.service.ICustomerFundDetailService;
import com.hb.batch.service.ICustomerFundService;
import com.hb.batch.service.IOrderService;
import com.hb.unic.logger.Logger;
import com.hb.unic.logger.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * ========== 用户任务 ==========
 *
 * @author Mr.huang
 * @version com.hb.batch.task.UserTask.java, v1.0
 * @date 2019年08月24日 18时09分
 */
@Component
public class UserOrderTask implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserOrderTask.class);

    /**
     * 线程池任务调度
     */
    private ThreadPoolTaskScheduler threadPoolTaskScheduler = null;
    /**
     * 结果
     */
    private Map<String, ScheduledFuture> futureMap = new ConcurrentHashMap<>();

    @Autowired
    private IOrderService orderService;

    @Autowired
    private ICustomerFundService iCustomerFundService;

    @Autowired
    private ICustomerFundDetailService iCustomerFundDetailService;

    @Autowired
    private StockQueryTask stockQueryTask;

    @Autowired
    private ITaskScheduler iTaskScheduler;

    @Value("${userTask.cron.default}")
    private String defaultCron;

    @Value("${userTaskExecutor.thread.core_pool_size}")
    private int poolSize;

    @Value("${userTaskExecutor.thread.name.prefix}")
    private String threadNamePrefix;

    @Value("${risk.control.max.percent}")
    private String riskMaxPercent;

    @Value("${stockQueryTask.cron.per_5s}")
    private String per_5s;
    @Value("${stockQueryTask.cron.per_4s}")
    private String per_4s;
    @Value("${stockQueryTask.cron.per_3s}")
    private String per_3s;
    @Value("${stockQueryTask.cron.per_2s}")
    private String per_2s;
    @Value("${stockQueryTask.cron.per_1s}")
    private String per_1s;

    @Override
    public void afterPropertiesSet() throws Exception {
        threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setPoolSize(poolSize);
        threadPoolTaskScheduler.setThreadNamePrefix(threadNamePrefix);
        threadPoolTaskScheduler.initialize();
    }

    /**
     * ########## 开启用户任务 ##########
     *
     * @param userId 用户ID
     */
    public void startUserTask(String userId) {
        Runnable runnable = new UserOrderRunnable(orderService,
                iCustomerFundService, iCustomerFundDetailService, stockQueryTask, userId, riskMaxPercent, per_5s,
                per_4s, per_3s, per_2s, per_1s);
        String taskId = getTaskId(userId);
        iTaskScheduler.start(defaultCron, runnable, taskId, threadPoolTaskScheduler, futureMap);
        LOGGER.info("start-用户任务：{}", taskId);
    }

    /**
     * ########## 添加用户任务 ##########
     *
     * @param userId 用户ID
     */
    public void addUserTask(String userId) {
        LOGGER.info("新增用户任务：{}", userId);
        startUserTask(userId);
    }

    /**
     * ########## 获取任务ID ##########
     *
     * @param userId 用户ID
     * @return taskId
     */
    private String getTaskId(String userId) {
        return "userTask_" + userId;
    }

}