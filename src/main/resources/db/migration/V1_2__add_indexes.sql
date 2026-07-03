CREATE INDEX idx_d2pv_discountid ON DiscountToProductVariantEntity(discountId);
CREATE INDEX idx_d2pv_productvariantid ON DiscountToProductVariantEntity(productVariantId);

CREATE INDEX idx_d2p_discountid ON DiscountToProductEntity(discountId);
CREATE INDEX idx_d2p_productid ON DiscountToProductEntity(productId);

CREATE INDEX idx_d2c_discountid ON DiscountToCategoryEntity(discountId);
CREATE INDEX idx_d2c_categoryid ON DiscountToCategoryEntity(categoryId);

CREATE INDEX idx_pv_productid ON ProductVariantEntity(productId);
CREATE INDEX idx_ptc_productid ON ProductToCategoryEntity(productId);
CREATE INDEX idx_ptc_categoryid ON ProductToCategoryEntity(categoryId);

CREATE INDEX idx_coupon_discountid ON CouponEntity(discountId);
CREATE INDEX idx_cr_couponid ON CouponRedemptionEntity(couponId);
CREATE INDEX idx_cr_userid ON CouponRedemptionEntity(userId);

CREATE INDEX idx_du_discountid ON DiscountUsageEntity(discountId);
CREATE INDEX idx_du_userid ON DiscountUsageEntity(userId);

CREATE INDEX idx_discount_validfrom ON DiscountEntity(validFrom);
CREATE INDEX idx_discount_validuntil ON DiscountEntity(validUntil);
