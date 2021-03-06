package com.imooc.miaosha.mq;

import com.alibaba.fastjson.JSON;
import com.imooc.miaosha.config.MqConfig;
import com.imooc.miaosha.dataobject.StockLog;
import com.imooc.miaosha.dto.OrderDTO;
import com.imooc.miaosha.dto.StockLogDTO;
import com.imooc.miaosha.enums.StockLogStatusEnum;
import com.imooc.miaosha.exception.MiaoshaException;
import com.imooc.miaosha.service.Impl.OrderServiceImpl;
import com.imooc.miaosha.service.Impl.StockLogServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author DateBro
 * @Date 2021/2/20 21:24
 */
@Component
@Slf4j
public class MqProducer {

    @Autowired
    private MqConfig mqConfig;

    private DefaultMQProducer producer;

    private TransactionMQProducer transactionMQProducer;

    @Autowired
    private OrderServiceImpl orderService;

    @Autowired
    private StockLogServiceImpl stockLogService;

    @PostConstruct
    void init() throws MQClientException {
        //做mq producer的初始化
        producer = new DefaultMQProducer("producer_group");
        producer.setNamesrvAddr(mqConfig.getNameServer().get("addr"));
        producer.start();

        // 做transactionMqProducer的初始化
        transactionMQProducer = new TransactionMQProducer("transaction_producer_group");
        transactionMQProducer.setNamesrvAddr(mqConfig.getNameServer().get("addr"));
        transactionMQProducer.start();
        // sendMessageInTransaction发出的消息一开始是prepare状态，不会被消费者看到，
        //这个消息被维护在message broker中间件中，
        //prepare状态会在本地去执行executeLocalTransaction方法，也就是真正要执行的事情
        transactionMQProducer.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message message, Object o) {
                Map<String, Object> argsMap = (Map) o;
                OrderDTO orderDTO = (OrderDTO) argsMap.get("orderDTO");
                Integer promoId = (Integer) argsMap.get("promoId");
                StockLogDTO stockLogDTO = (StockLogDTO) argsMap.get("stockLogDTO");
                try {
                    orderService.create(orderDTO, promoId, stockLogDTO);
                } catch (MiaoshaException e) {
                    log.error("【mq事务型消息生成订单】下单失败");

                    // 如果下单失败，需要将库存流水状态设为回滚
                    stockLogService.updateStockLogStatus(stockLogDTO.getStockLogId(), stockLogDTO.getStatus(), StockLogStatusEnum.ROLLBACK.getStatus());
                    stockLogDTO.setStatus(StockLogStatusEnum.ROLLBACK.getStatus());

                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
                return LocalTransactionState.COMMIT_MESSAGE;
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt messageExt) {
                // 根据库存流水的状态返回rollback或commit
                String jsonString = new String(messageExt.getBody());
                Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
                Integer productId = (Integer) map.get("productId");
                Integer productQuantity = (Integer) map.get("productQuantity");
                String stockLogId = (String) map.get("stockLogId");
                StockLogDTO stockLogDTO = stockLogService.getStockLogDTOByStockLogId(stockLogId);
                if (stockLogDTO == null) {
                    return LocalTransactionState.UNKNOW;
                }
                if (stockLogDTO.getStatus() == StockLogStatusEnum.INIT.getStatus()) {
                    return LocalTransactionState.UNKNOW;
                } else if (stockLogDTO.getStatus() == StockLogStatusEnum.COMMIT.getStatus()) {
                    return LocalTransactionState.COMMIT_MESSAGE;
                }
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
        });
    }

    public boolean transactionAsyncReduceStock(OrderDTO orderDTO, Integer promoId, StockLogDTO stockLogDTO) {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("productId", orderDTO.getProductId());
        bodyMap.put("productQuantity", orderDTO.getProductQuantity());
        bodyMap.put("stockLogId", stockLogDTO.getStockLogId());

        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("orderDTO", orderDTO);
        argsMap.put("promoId", promoId);
        argsMap.put("stockLogDTO", stockLogDTO);

        Message message = new Message(mqConfig.getTopicName(), "increase",
                JSON.toJSON(bodyMap).toString().getBytes(StandardCharsets.UTF_8));
        TransactionSendResult sendResult = null;
        try {
            // message是待投递的事务消息，args是供本地事务执行程序使用的参数对象
            // 也就是message能被consumer收到，args是上面listener用的
            sendResult = transactionMQProducer.sendMessageInTransaction(message, argsMap);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        }
        if (sendResult.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
            return false;
        } else return sendResult.getLocalTransactionState() == LocalTransactionState.COMMIT_MESSAGE;
    }

    //同步库存扣减消息
    public boolean asyncReduceStock(Integer productId, Integer productQuantity) {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("productId", productId);
        bodyMap.put("productQuantity", productQuantity);

        Message message = new Message(mqConfig.getTopicName(), "increase",
                JSON.toJSON(bodyMap).toString().getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(message);
        } catch (MQClientException e) {
            e.printStackTrace();
            return false;
        } catch (RemotingException e) {
            e.printStackTrace();
            return false;
        } catch (MQBrokerException e) {
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
