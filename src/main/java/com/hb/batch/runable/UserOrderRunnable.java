package com.hb.batch.runable;

import com.hb.batch.service.ICustomerFundDetailService;
import com.hb.batch.service.ICustomerFundService;
import com.hb.batch.service.IOrderService;
import com.hb.batch.task.OrderQueryTask;
import com.hb.batch.task.StockQueryTask;
import com.hb.facade.entity.CustomerFundDO;
import com.hb.facade.entity.CustomerFundDetailDO;
import com.hb.facade.entity.OrderDO;
import com.hb.facade.enumutil.FundTypeEnum;
import com.hb.facade.enumutil.OrderStatusEnum;
import com.hb.remote.model.StockModel;
import com.hb.unic.logger.Logger;
import com.hb.unic.logger.LoggerFactory;
import com.hb.unic.util.helper.LogHelper;
import com.hb.unic.util.util.BigDecimalUtils;
import com.hb.unic.util.util.DateUtils;
import org.apache.commons.collections4.CollectionUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ========== 订单风控 ==========
 *
 * @author Mr.huang
 * @version com.hb.batch.runable.UserOrderRunnable.java, v1.0
 * @date 2019年08月24日 18时37分
 */
public class UserOrderRunnable implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserOrderRunnable.class);

    private IOrderService iOrderService;
    private ICustomerFundService iCustomerFundService;
    private ICustomerFundDetailService iCustomerFundDetailService;
    private StockQueryTask stockQueryTask;
    private String userId;
    private String riskMaxPercent;
    private String per_5s;
    private String per_4s;
    private String per_3s;
    private String per_2s;
    private String per_1s;

    public UserOrderRunnable(IOrderService iOrderService, ICustomerFundService iCustomerFundService, ICustomerFundDetailService iCustomerFundDetailService, StockQueryTask stockQueryTask, String userId, String riskMaxPercent, String per_5s, String per_4s, String per_3s, String per_2s, String per_1s) {
        this.iOrderService = iOrderService;
        this.iCustomerFundService = iCustomerFundService;
        this.iCustomerFundDetailService = iCustomerFundDetailService;
        this.stockQueryTask = stockQueryTask;
        this.userId = userId;
        this.riskMaxPercent = riskMaxPercent;
        this.per_5s = per_5s;
        this.per_4s = per_4s;
        this.per_3s = per_3s;
        this.per_2s = per_2s;
        this.per_1s = per_1s;
    }

    @Override
    public void run() {
        List<OrderDO> orderList = OrderQueryTask.getOrderListByUserId(userId);
        LOGGER.info("用户ID：{}，风险控制开始", userId);
        if (CollectionUtils.isEmpty(orderList)) {
            return;
        }
        Set<String> stockCodeSet = new HashSet<>();
        orderList.forEach(orderDO -> stockCodeSet.add(orderDO.getStockCode()));
        for (OrderDO orderDO : orderList) {
            String userName = orderDO.getUserName();
            String orderId = orderDO.getOrderId();
            String stockCode = orderDO.getStockCode();
            try {
                LOGGER.info("订单号：{}，股票代码：{}，风险控制开始", orderId, stockCode);
                StockModel stockModel = StockQueryTask.getStock(stockCode);
                // 当前价格
                BigDecimal currentPrice = stockModel.getCurrentPrice();
                // 止盈价格
                BigDecimal stopEarnMoney = orderDO.getStopEarnMoney();
                // 止损价格
                BigDecimal stopLossMoney = orderDO.getStopLossMoney();
                if (BigDecimal.ZERO.compareTo(stopEarnMoney) != 0 && currentPrice.compareTo(stopEarnMoney) >= 0) {
                    // 当前价格>=止盈价格，平仓
                    LOGGER.info("用户姓名：{}，订单号：{}，当前价格({})>=止盈价格({})，进行平仓操作", userName, orderId, currentPrice, stopEarnMoney);
                    completeOrder(orderDO, stockModel);
                    continue;
                }
                if (BigDecimal.ZERO.compareTo(stopLossMoney) != 0 && currentPrice.compareTo(stopLossMoney) <= 0) {
                    // 当前价格<=止损价格，平仓
                    LOGGER.info("用户姓名：{}，订单号：{}，当前价格({})<=止损价格({})，进行平仓操作", userName, orderId, currentPrice, stopLossMoney);
                    completeOrder(orderDO, stockModel);
                    continue;
                }
                BigDecimal buyPrice = orderDO.getBuyPrice();
                Integer buyNumber = orderDO.getBuyNumber();
                if (currentPrice.compareTo(buyPrice) >= 0) {
                    continue;
                }
                BigDecimal lossMoneyUnit = BigDecimalUtils.subtract(currentPrice, buyPrice, 4);
                BigDecimal lossMoney = BigDecimalUtils.multiply(lossMoneyUnit, new BigDecimal(buyNumber));
                BigDecimal strategyOwnMoney = orderDO.getStrategyOwnMoney();
                BigDecimal riskPercent = new BigDecimal(riskMaxPercent);
                BigDecimal maxLossMoney = BigDecimalUtils.multiply(strategyOwnMoney, riskPercent);
                BigDecimal stockQueryStrategy = BigDecimalUtils.divide(BigDecimalUtils.divide(lossMoney, maxLossMoney).abs(), riskPercent);
                LOGGER.info("订单号：{}，股票代码：{}，风险控制策略：{}", orderId, stockCode, stockQueryStrategy);
                changeQueryStockStrategy(userId, stockCodeSet, stockQueryStrategy);
                if (BigDecimal.ZERO.compareTo(lossMoney) < 0 && lossMoney.abs().compareTo(maxLossMoney) >= 0) {
                    // 平仓
                    LOGGER.info("用户姓名：{}，订单号：{}，当前价格({})已经达到亏损阀值（{}），进行平仓操作", userName, orderId, currentPrice, riskMaxPercent);
                    completeOrder(orderDO, stockModel);
                    continue;
                }
                LOGGER.info("订单号：{}，股票代码：{}，风险控制结束", orderId, stockCode);
            } catch (Exception e) {
                LOGGER.error("用户：{}，订单号：{}，风控过程中出现异常：{}", userName, orderId, LogHelper.getStackTrace(e));
            }
        }
    }

    /**
     * ########## 改变股票查询轮询间隔 ##########
     */
    private void changeQueryStockStrategy(String userId, Set<String> stockCodeSet, BigDecimal abs) {

        if (abs.compareTo(new BigDecimal("0.2")) > 0) {
            stockQueryTask.stopTask(userId);
            stockQueryTask.startTask(per_5s, stockCodeSet, userId);
        } else if (abs.compareTo(new BigDecimal("0.4")) > 0) {
            stockQueryTask.stopTask(userId);
            stockQueryTask.startTask(per_4s, stockCodeSet, userId);
        } else if (abs.compareTo(new BigDecimal("0.6")) > 0) {
            stockQueryTask.stopTask(userId);
            stockQueryTask.startTask(per_3s, stockCodeSet, userId);
        } else if (abs.compareTo(new BigDecimal("0.8")) > 0) {
            stockQueryTask.stopTask(userId);
            stockQueryTask.startTask(per_2s, stockCodeSet, userId);
        } else if (abs.compareTo(new BigDecimal("0.9")) > 0) {
            stockQueryTask.stopTask(userId);
            stockQueryTask.startTask(per_1s, stockCodeSet, userId);
        }

    }

    /**
     * ########## 完成订单 ##########
     *
     * @param orderDO    订单信息
     * @param stockModel 股票信息
     */
    private void completeOrder(OrderDO orderDO, StockModel stockModel) {
        // 当前价格
        BigDecimal currentPrice = stockModel.getCurrentPrice();
        // 买入股数
        BigDecimal buyNumber = new BigDecimal(orderDO.getBuyNumber());
        // 买入价格
        BigDecimal buyPrice = orderDO.getBuyPrice();
        // 盈亏的总金额，盈利为正，亏损为负
        BigDecimal lossOrEarnMoneyTotal = BigDecimalUtils.multiply(BigDecimalUtils.subtract(currentPrice, buyPrice, 4), buyNumber);
        // 策略本金
        BigDecimal strategyOwnMoney = orderDO.getStrategyOwnMoney();
        // 策略金额
        BigDecimal strategyMoney = orderDO.getStrategyMoney();
        // 买入总金额
        BigDecimal buyPriceTotal = orderDO.getBuyPriceTotal();
        /**
         * 更新订单
         */
        String userId = orderDO.getUserId();
        String orderId = orderDO.getOrderId();
        OrderDO update = new OrderDO(orderId, userId);
        // 卖出价格
        update.setSellPrice(currentPrice);
        // 卖出总价格 = 买入总金额+盈亏的总金额
        BigDecimal sellPriceTotal = BigDecimalUtils.add(buyPriceTotal, lossOrEarnMoneyTotal);
        update.setSellPriceTotal(sellPriceTotal);
        // 订单状态
        update.setOrderStatus(OrderStatusEnum.ALREADY_SETTLED.getValue());
        // 利润
        update.setProfit(lossOrEarnMoneyTotal);
        // 盈亏率 = 盈亏总金额/买入总金额
        BigDecimal profitRate = BigDecimalUtils.divide(lossOrEarnMoneyTotal, buyPriceTotal, 4);
        update.setProfitRate(profitRate);
        int updateOrderResult = iOrderService.updateByPrimaryKeySelective(update);
        LOGGER.info("用户姓名：{}，订单号：{}，更新订单：{}", userId, orderId, update);
        if (updateOrderResult <= 0) {
            return;
        }
        // 递延金总金额 = 递延金*天数
        BigDecimal delayMoney = orderDO.getDelayMoney();
        Date createTime = orderDO.getCreateTime();
        int daysBetween = DateUtils.getDaysBetween(new Date(), createTime);
        BigDecimal delayMoneyTotal = BigDecimalUtils.multiply(delayMoney, new BigDecimal(daysBetween));
        /**
         * 更新客户账户信息
         */
        CustomerFundDO query = new CustomerFundDO(userId);
        CustomerFundDO customerFund = iCustomerFundService.findCustomerFund(query);
        CustomerFundDO updateFund = new CustomerFundDO(userId);
        // 总变化量 = 盈亏总金额-信息服务费-递延金
        BigDecimal changeMoneyTotal = BigDecimalUtils.subtract(BigDecimalUtils.subtract(lossOrEarnMoneyTotal, delayMoneyTotal), orderDO.getServiceMoney());
        // 账户总金额 = 原账户总金额+总变化量
        updateFund.setAccountTotalMoney(BigDecimalUtils.add(customerFund.getAccountTotalMoney(), changeMoneyTotal));
        // 冻结金额 = 原冻结金额-订单的策略本金
        updateFund.setFreezeMoney(BigDecimalUtils.subtract(customerFund.getFreezeMoney(), strategyOwnMoney));
        // 可用余额 = 原可用余额+订单的策略本金+总变化量
        BigDecimal newUseableMoney = BigDecimalUtils.add(customerFund.getUsableMoney(), BigDecimalUtils.add(strategyOwnMoney, changeMoneyTotal));
        updateFund.setUsableMoney(newUseableMoney);
        // 累计盈亏 = 原累计盈亏+此次盈亏
        updateFund.setTotalProfitAndLossMoney(BigDecimalUtils.add(customerFund.getTotalProfitAndLossMoney(), lossOrEarnMoneyTotal));
        // 累计信息服务费 = 原累计信息服务费+此次信息服务费
        updateFund.setTotalMessageServiceMoney(BigDecimalUtils.add(customerFund.getTotalMessageServiceMoney(), orderDO.getServiceMoney()));
        int updateCustomerFundResult = iCustomerFundService.updateByPrimaryKeySelective(updateFund);
        LOGGER.info("用户姓名：{}，订单号：{}，更新客户资金信息：{}", userId, orderId, updateFund);
        if (updateCustomerFundResult <= 0) {
            return;
        }
        /**
         * 更新客户资金流水信息
         */
        CustomerFundDetailDO add = new CustomerFundDetailDO();
        add.setUserId(userId);
        add.setUserName(orderDO.getUserName());
        add.setHappenMoney(delayMoneyTotal);
        add.setFundType(FundTypeEnum.DELAY.getValue());
        add.setRemark(FundTypeEnum.DELAY.getDesc());
        iCustomerFundDetailService.addOne(add);
        LOGGER.info("用户姓名：{}，订单号：{}，新增客户资金流水：{}", userId, orderId, add);
        CustomerFundDetailDO add1 = new CustomerFundDetailDO();
        add1.setUserId(userId);
        add1.setUserName(orderDO.getUserName());
        add1.setHappenMoney(orderDO.getServiceMoney());
        add1.setFundType(FundTypeEnum.FREEZE.getValue());
        add1.setRemark(FundTypeEnum.FREEZE.getDesc());
        iCustomerFundDetailService.addOne(add1);
        LOGGER.info("用户姓名：{}，订单号：{}，新增客户资金流水：{}", userId, orderId, add1);
    }

}
