package com.imooc.miaosha.service.Impl;

import com.imooc.miaosha.constant.RedisConstant;
import com.imooc.miaosha.converter.PromoInfo2PromoDTOConverter;
import com.imooc.miaosha.dataobject.PromoInfo;
import com.imooc.miaosha.dto.BuyerDTO;
import com.imooc.miaosha.dto.ProductDTO;
import com.imooc.miaosha.dto.PromoDTO;
import com.imooc.miaosha.enums.ResultEnum;
import com.imooc.miaosha.exception.MiaoshaException;
import com.imooc.miaosha.repository.PromoInfoRepository;
import com.imooc.miaosha.service.PromoService;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @Author DateBro
 * @Date 2021/2/18 16:16
 */
@Service
@Slf4j
public class PromoServiceImpl implements PromoService {

    @Autowired
    private PromoInfoRepository promoInfoRepository;

    @Autowired
    private ProductServiceImpl productService;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private BuyerServiceImpl buyerService;

    @Override
    public PromoDTO getPromoByProductId(Integer productId) {
        PromoInfo promoInfo = promoInfoRepository.findByProductId(productId);
        PromoDTO promoDTO = new PromoDTO();
        if (promoInfo == null) {
            promoDTO = null;
        } else {
            promoDTO = PromoInfo2PromoDTOConverter.convert(promoInfo);
        }

        return promoDTO;
    }

    /**
     * 活动发布同步库存进缓存
     *
     * @param promoId
     * @return
     */
    @Override
    public PromoDTO publishPromo(Integer promoId) {
        if (promoId == null) {
            throw new MiaoshaException(ResultEnum.PARAMETER_VALIDATION_ERROR);
        }
        PromoInfo promoInfo = promoInfoRepository.getOne(promoId);
        if (promoInfo == null) {
            throw new MiaoshaException(ResultEnum.PROMO_NOT_EXIST);
        }
        ProductDTO productDTO = productService.getProductDetail(promoInfo.getProductId());
        // 假设这段时间内stock不会变化
        redisTemplate.opsForValue().set(String.format(RedisConstant.PROMO_PRODUCT_STOCK_PREFIX, productDTO.getProductId()), productDTO.getStock());

        PromoDTO promoDTO = PromoInfo2PromoDTOConverter.convert(promoInfo);

        //将大闸的限制数字设到redis内
        redisTemplate.opsForValue().set(String.format(RedisConstant.PROMO_PRODUCT_TOKEN_LATCH_PATTERN, productDTO.getProductId(), promoId),
                productDTO.getStock().intValue() * 5);
        return promoDTO;
    }

    @Override
    public String genPromoToken(Integer promoId, Integer productId, Integer buyerId) {
        // 检查库存售罄标识
        if (redisTemplate.hasKey(String.format(RedisConstant.PRODUCT_STOCK_INVALID_PREFIX, productId))) {
            throw new MiaoshaException(ResultEnum.STOCK_NOT_ENOUGH);
        }
        PromoInfo promoInfo = promoInfoRepository.getOne(promoId);
        PromoDTO promoDTO = new PromoDTO();
        if (promoInfo == null) {
            promoDTO = null;
        } else {
            promoDTO = PromoInfo2PromoDTOConverter.convert(promoInfo);
        }
        //判断活动是否正在进行
        if (promoDTO.getPromoStatus().intValue() != 2) {
            return null;
        }
        //判断商品信息是否存在
        ProductDTO productDTO = productService.getProductDetailInCache(productId);
        if (productDTO == null) {
            return null;
        }
        //判断用户信息是否存在
        BuyerDTO buyerDTO = buyerService.getBuyerDetailByIdInCache(buyerId);
        if (buyerDTO == null) {
            return null;
        }

        //获取秒杀大闸的count数量
        long result = redisTemplate.opsForValue().increment(String.format(RedisConstant.PROMO_PRODUCT_TOKEN_LATCH_PATTERN, productDTO.getProductId(), promoId), -1);
        if (result < 0) {
            return null;
        }

        //生成token并且存入redis内
        String token = UUID.randomUUID().toString().replace("-", "");

        redisTemplate.opsForValue().set(String.format(RedisConstant.PROMO_PRODUCT_TOKEN_PATTER, productId, promoId, buyerId),
                token,
                RedisConstant.PROMO_PRODUCT_TOKEN_EXPIRE,
                TimeUnit.SECONDS);
        return token;
    }
}
