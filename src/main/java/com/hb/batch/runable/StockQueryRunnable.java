package com.hb.batch.runable;

import com.hb.batch.constant.BatchConstant;
import com.hb.batch.service.IStockListService;
import com.hb.batch.task.StockQueryTask;
import com.hb.facade.entity.StockListDO;
import com.hb.remote.model.StockModel;
import com.hb.remote.service.IStockService;
import com.hb.unic.logger.Logger;
import com.hb.unic.logger.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ========== 股票查询runable ==========
 *
 * @author Mr.huang
 * @version com.hb.batch.runable.StockQueryRunnable.java, v1.0
 * @date 2019年08月20日 01时33分
 */
public class StockQueryRunnable implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockQueryRunnable.class);

    private int batchCount = BatchConstant.QUERY_BATCH_COUNT;

    private Set<String> stockCodeSet;

    private IStockService stockService;

    private IStockListService stockListService;

    public StockQueryRunnable(Set<String> stockCodeSet, IStockService stockService, IStockListService stockListService) {
        this.stockCodeSet = stockCodeSet;
        this.stockService = stockService;
        this.stockListService = stockListService;
    }

    @Override
    public void run() {
        LOGGER.info("当前线程：{}", Thread.currentThread().getName());
        LOGGER.info("待查询股票代码：" + stockCodeSet);
        List<StockListDO> stockListDOList = stockListService.getStockListBySet(stockCodeSet);
        List<String> stockCodeList = new ArrayList<>();
        for (StockListDO stockListDO : stockListDOList) {
            stockCodeList.add(stockListDO.getFull_code());
        }
        int total = stockCodeList.size();
        int i = 1;
        while (true) {
            if (total > i * batchCount) {
                List<String> subList = stockCodeList.subList((i - 1) * batchCount, i * batchCount);
                Set<String> subSet = new HashSet<>(subList);
                List<StockModel> stockModelList = stockService.queryStockList(subSet);
                StockQueryTask.updateStockMap(stockModelList);
            } else {
                List<String> subList = stockCodeList.subList((i - 1) * batchCount, stockCodeList.size() - 1);
                Set<String> subSet = new HashSet<>(subList);
                List<StockModel> stockModelList = stockService.queryStockList(subSet);
                StockQueryTask.updateStockMap(stockModelList);
                break;
            }
            i++;
        }
    }

}
