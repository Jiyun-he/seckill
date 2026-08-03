package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.seckill.entity.Goods;
import com.example.seckill.vo.GoodsVO;

/**
 * 商品服务接口。
 *
 * @author jiyunhe
 */

public interface GoodsService extends IService<Goods> {

    /**
     * 分页查询商品列表，支持按商品名称关键字模糊搜索，结果按创建时间倒序排列。
     *
     * @param page    页码，从 1 开始
     * @param size    每页记录数
     * @param keyword 搜索关键字，按商品名称模糊匹配；为空或 null 时返回全部商品
     * @return 分页后的商品列表，其中记录为 {@link GoodsVO}
     */
    Page<GoodsVO> listGoods(Integer page, Integer size, String keyword);

    /**
     * 根据商品 ID 查询商品详情。
     *
     * @param id 商品 ID
     * @return 商品详情 VO；商品不存在时返回 null
     */
    GoodsVO getGoodsDetail(Long id);
}
