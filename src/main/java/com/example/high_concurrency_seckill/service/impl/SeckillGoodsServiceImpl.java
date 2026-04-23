package com.example.high_concurrency_seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.high_concurrency_seckill.entity.SeckillGoods;
import com.example.high_concurrency_seckill.mapper.SeckillGoodsMapper;
import com.example.high_concurrency_seckill.service.SeckillGoodsService;
import org.springframework.stereotype.Service;

@Service
public class SeckillGoodsServiceImpl extends ServiceImpl<SeckillGoodsMapper, SeckillGoods> implements SeckillGoodsService {
}