package com.example.seckill;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.entity.Order;
import com.example.seckill.entity.SeckillGoods;
import com.example.seckill.mapper.OrderMapper;
import com.example.seckill.mapper.SeckillGoodsMapper;
import com.example.seckill.service.SeckillGoodsService;
import com.example.seckill.service.SeckillOrderConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 消费事务回滚测试（#12）。
 *
 * <p>模拟订单插入成功后、库存扣减抛异常的场景，验证整个事务回滚、不留下
 * 「订单已存在但库存未扣」的半完成状态。通过 mock {@link SeckillGoodsService} 的
 * {@code update} 抛异常来实现插入之后的失败窗口。</p>
 *
 * @author jiyunhe
 */
@SpringBootTest(classes = SeckillTestApplication.class)
class SeckillOrderConsumerRollbackTest extends AbstractIntegrationTest {

    private static final long USER_ID = 2001L;
    private static final long GOODS_ID = 1L;
    private static final String VERSION = "20260101000000";

    @Autowired
    private SeckillOrderConsumer consumer;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    @MockitoBean
    private SeckillGoodsService seckillGoodsService;

    @Test
    void consume_下游异常导致整事务回滚() {
        long orderNo = 91001L;

        SeckillGoods sg = new SeckillGoods();
        sg.setId(GOODS_ID);
        sg.setGoodsId(1001L);
        sg.setSeckillPrice(new BigDecimal("99.99"));
        sg.setSeckillStock(50);

        when(seckillGoodsService.getById(GOODS_ID)).thenReturn(sg);
        doThrow(new RuntimeException("模拟库存扣减异常"))
                .when(seckillGoodsService).update(ArgumentMatchers.<Wrapper<SeckillGoods>>any());

        assertThatThrownBy(() -> consumer.handleSeckillOrder(msg(orderNo)))
                .isInstanceOf(RuntimeException.class);

        // 订单插入被回滚，不留下半完成状态
        assertThat(countOrders(orderNo)).isZero();
        // DB 库存未被扣减（真实 mapper 读值）
        assertThat(seckillGoodsMapper.selectById(GOODS_ID).getSeckillStock()).isEqualTo(50);
    }

    private Map<String, Object> msg(long orderNo) {
        Map<String, Object> m = new HashMap<>();
        m.put("userId", USER_ID);
        m.put("seckillGoodsId", GOODS_ID);
        m.put("orderNo", orderNo);
        m.put("startTime", VERSION);
        return m;
    }

    private long countOrders(long orderNo) {
        return orderMapper.selectCount(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }
}
