package com.imooc.miaosha.mq;

import com.alibaba.fastjson.JSON;
import com.imooc.miaosha.config.MqConfig;
import com.imooc.miaosha.service.Impl.ProductServiceImpl;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * @Author DateBro
 * @Date 2021/2/20 21:24
 */
@Component
public class MqConsumer {

    private DefaultMQPushConsumer consumer;

    @Autowired
    private MqConfig mqConfig;

    @Autowired
    private ProductServiceImpl productService;

    @PostConstruct
    void init() throws MQClientException {
        consumer = new DefaultMQPushConsumer();
        consumer.setNamesrvAddr(mqConfig.getNameServer().get("addr"));
        consumer.setConsumerGroup("stock_consumer_group");
        consumer.subscribe(mqConfig.getTopicName(), "*");

        // Register callback to execute on arrival of messages fetched from brokers.
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                            ConsumeConcurrentlyContext context) {
                //实现库存真正到数据库内扣减的逻辑
                Message msg = msgs.get(0);
                String jsonString = new String(msg.getBody());
                Map<String, Object> map = JSON.parseObject(jsonString, Map.class);
                Integer productId = (Integer) map.get("productId");
                Integer productQuantity = (Integer) map.get("productQuantity");

                productService.decreaseStock(productId, productQuantity);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
    }
}