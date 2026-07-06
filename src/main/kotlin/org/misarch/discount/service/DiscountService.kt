package org.misarch.discount.service

import com.expediagroup.graphql.generator.execution.OptionalInput
import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.sql.SQLExpressions
import kotlinx.coroutines.reactor.awaitSingle
import org.misarch.discount.event.DiscountEvents
import org.misarch.discount.event.EventPublisher
import org.misarch.discount.event.model.ValidationFailedDTO
import org.misarch.discount.event.model.ValidationSucceededDTO
import org.misarch.discount.event.model.order.OrderDTO
import org.misarch.discount.graphql.AuthorizedUser
import org.misarch.discount.graphql.input.CreateDiscountInput
import org.misarch.discount.graphql.input.FindApplicableDiscountsInput
import org.misarch.discount.graphql.input.FindApplicableDiscountsProductVariantInput
import org.misarch.discount.graphql.input.UpdateDiscountInput
import org.misarch.discount.graphql.model.DiscountsForProductVariant
import org.misarch.discount.persistence.model.*
import org.misarch.discount.persistence.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.*
import kotlin.system.measureTimeMillis

/**
 * Service for [DiscountEntity]s
 *
 * @param repository the provided repository
 * @param categoryRepository the category repository
 * @param productRepository the product repository
 * @param productVariantRepository the product variant repository
 * @param discountToCategoryRepository the discount to category repository
 * @param discountToProductRepository the discount to product repository
 * @param discountToProductVariantRepository the discount to product variant repository
 * @param discountUsageRepository the discount usage repository
 * @param couponRepository the coupon repository
 * @param eventPublisher the event publisher
 */
@Service
class DiscountService(
    repository: DiscountRepository,
    private val categoryRepository: CategoryRepository,
    private val productRepository: ProductRepository,
    private val productVariantRepository: ProductVariantRepository,
    private val discountToCategoryRepository: DiscountToCategoryRepository,
    private val discountToProductRepository: DiscountToProductRepository,
    private val discountToProductVariantRepository: DiscountToProductVariantRepository,
    private val discountUsageRepository: DiscountUsageRepository,
    private val couponRepository: CouponRepository,
    private val eventPublisher: EventPublisher
) : BaseService<DiscountEntity, DiscountRepository>(repository) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Creates a discount
     *
     * @param discountInput the discount to create
     * @return the created discount
     */
    suspend fun createDiscount(discountInput: CreateDiscountInput): DiscountEntity {
        ensureReferencedEntitiesExist(discountInput)
        require(discountInput.discount >= 0) { "A discount cannot increase the price and thus must be >= 0" }
        require(discountInput.discount <= 1) { "A discount cannot be larger than 1" }
        val discount = DiscountEntity(
            discount = discountInput.discount,
            maxUsagesPerUser = discountInput.maxUsagesPerUser,
            validUntil = discountInput.validUntil,
            validFrom = discountInput.validFrom,
            minOrderAmount = discountInput.minOrderAmount,
            id = null
        )
        val savedDiscount = repository.save(discount).awaitSingle()
        val categoryIds = discountInput.discountAppliesToCategoryIds.toSet()
        val productIds = discountInput.discountAppliesToProductIds.toSet()
        val productVariantIds = discountInput.discountAppliesToProductVariantIds.toSet()
        addAppliedToReferences(savedDiscount.id!!, categoryIds, productIds, productVariantIds)
        eventPublisher.publishEvent(
            DiscountEvents.DISCOUNT_CREATED, savedDiscount.toEventDTO(categoryIds, productIds, productVariantIds)
        )
        return savedDiscount
    }

    /**
     * Updates a discount
     *
     * @param discountInput the input for the update
     * @return the updated discount
     */
    suspend fun updateDiscount(discountInput: UpdateDiscountInput): DiscountEntity {
        val discount = repository.findById(discountInput.id).awaitSingle()
        if (discountInput.maxUsagesPerUser is OptionalInput.Defined) {
            discount.maxUsagesPerUser = discountInput.maxUsagesPerUser.value
        }
        if (discountInput.validUntil != null) {
            discount.validUntil = discountInput.validUntil
        }
        if (discountInput.validFrom != null) {
            discount.validFrom = discountInput.validFrom
        }
        if (discountInput.minOrderAmount is OptionalInput.Defined) {
            discount.minOrderAmount = discountInput.minOrderAmount.value
        }
        updateDiscountReferencedEntities(discountInput)
        val updatedDiscount = repository.save(discount).awaitSingle()
        eventPublisher.publishEvent(
            DiscountEvents.DISCOUNT_UPDATED, updatedDiscount.toEventDTO(
                discountToCategoryRepository.findByDiscountId(discountInput.id).map { it.categoryId }.toSet(),
                discountToProductRepository.findByDiscountId(discountInput.id).map { it.productId }.toSet(),
                discountToProductVariantRepository.findByDiscountId(discountInput.id).map { it.productVariantId }.toSet()
            )
        )
        return updatedDiscount
    }

    /**
     * Updates the entities the discount applies to, and removes the entities the discount no longer applies to
     *
     * @param discountInput the input for the update
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun updateDiscountReferencedEntities(discountInput: UpdateDiscountInput) {
        val categoryIds = discountInput.addedDiscountAppliesToCategoryIds?.toSet() ?: emptySet()
        val productIds = discountInput.addedDiscountAppliesToProductIds?.toSet() ?: emptySet()
        val productVariantIds = discountInput.addedDiscountAppliesToProductVariantIds?.toSet() ?: emptySet()
        ensureEntitiesExist(categoryIds, productIds, productVariantIds)
        addAppliedToReferences(discountInput.id, categoryIds, productIds, productVariantIds)
        discountInput.removedDiscountAppliesToCategoryIds?.forEach {
            discountToCategoryRepository.deleteByDiscountIdAndCategoryId(discountInput.id, it)
        }
        discountInput.removedDiscountAppliesToProductIds?.forEach {
            discountToProductRepository.deleteByDiscountIdAndProductId(discountInput.id, it)
        }
        discountInput.removedDiscountAppliesToProductVariantIds?.forEach {
            discountToProductVariantRepository.deleteByDiscountIdAndProductVariantId(discountInput.id, it)
        }
    }

    /**
     * Checks that the entities referenced by [discountInput] exist
     *
     * @param discountInput the discount input
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun ensureReferencedEntitiesExist(discountInput: CreateDiscountInput) {
        val categoryIds = discountInput.discountAppliesToCategoryIds.toSet()
        val productIds = discountInput.discountAppliesToProductIds.toSet()
        val productVariantIds = discountInput.discountAppliesToProductVariantIds.toSet()
        require(categoryIds.isNotEmpty() || productIds.isNotEmpty() || productVariantIds.isNotEmpty()) {
            "At least one category, product or product variant must be specified"
        }
        ensureEntitiesExist(categoryIds, productIds, productVariantIds)
    }

    /**
     * Checks that the entities referenced by [categoryIds], [productIds] and [productVariantIds] exist
     *
     * @param categoryIds the category ids
     * @param productIds the product ids
     * @param productVariantIds the product variant ids
     * @throws IllegalArgumentException if any of the referenced entities do not exist
     */
    private suspend fun ensureEntitiesExist(
        categoryIds: Set<UUID>, productIds: Set<UUID>, productVariantIds: Set<UUID>
    ) {
        val existingCategoryIds = categoryRepository.findAllById(categoryIds).collectList().awaitSingle().map { it.id!! }.toSet()
        val missingCategories = categoryIds - existingCategoryIds
        require(missingCategories.isEmpty()) { "Categories with ids $missingCategories do not exist" }
        val existingProductIds = productRepository.findAllById(productIds).collectList().awaitSingle().map { it.id!! }.toSet()
        val missingProducts = productIds - existingProductIds
        require(missingProducts.isEmpty()) { "Products with ids $missingProducts do not exist" }
        val existingProductVariantIds = productVariantRepository.findAllById(productVariantIds).collectList().awaitSingle().map { it.id!! }.toSet()
        val missingProductVariants = productVariantIds - existingProductVariantIds
        require(missingProductVariants.isEmpty()) { "Product variants with ids $missingProductVariants do not exist" }
    }

    /**
     * Adds the applied to references to the discount
     *
     * @param id the id of the discount
     * @param discountAppliesToCategoryIds the category ids to which the discount applies
     * @param discountAppliesToProductIds the product ids to which the discount applies
     * @param discountAppliesToProductVariantIds the product variant ids to which the discount applies
     */
    private suspend fun addAppliedToReferences(
        id: UUID,
        discountAppliesToCategoryIds: Set<UUID>,
        discountAppliesToProductIds: Set<UUID>,
        discountAppliesToProductVariantIds: Set<UUID>
    ) {
        discountToCategoryRepository.saveAll(discountAppliesToCategoryIds.map {
            DiscountToCategoryEntity(discountId = id, categoryId = it, id = null)
        }).collectList().awaitSingle()
        discountToProductRepository.saveAll(discountAppliesToProductIds.map {
            DiscountToProductEntity(discountId = id, productId = it, id = null)
        }).collectList().awaitSingle()
        discountToProductVariantRepository.saveAll(discountAppliesToProductVariantIds.map {
            DiscountToProductVariantEntity(discountId = id, productVariantId = it, id = null)
        }).collectList().awaitSingle()
    }

    /**
     * Validates an order
     * Checks if the user can still use the discounts with the amount of items
     * Does NOT validate if the discount is even usable with the items in the order
     *
     * @param order the order to validate
     */
    suspend fun validateOrder(order: OrderDTO) {
        try {
            validateOrderInternal(order)
        } catch (e: Exception) {
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_FAILED, ValidationFailedDTO(order, emptyList()))
            throw e
        }
    }

    /**
     * Validates an order
     * Checks if the user can still use the discounts with the amount of items
     * Does NOT validate if the discount is even usable with the items in the order
     * Does not handle exceptions!
     *
     * @param order the order to validate
     */
    private suspend fun validateOrderInternal(order: OrderDTO) {
        val orderItemsByDiscount = order.orderItems.flatMap { orderItem ->
            orderItem.discountIds.map { it to orderItem }
        }.groupBy({ it.first }) { it.second }
        val discounts =
            repository.findAllById(orderItemsByDiscount.keys).collectList().awaitSingle().associateBy { it.id }
        val failedDiscounts = mutableListOf<UUID>()
        val remainingDiscountUsages = findRemainingUsagesForDiscounts(order.userId, discounts.values.toSet())
        orderItemsByDiscount.forEach { (discount, orderItems) ->
            val totalAmount = orderItems.sumOf { it.count }
            if (totalAmount > (remainingDiscountUsages[discount] ?: Long.MAX_VALUE)) {
                failedDiscounts += discount
            }
        }
        if (failedDiscounts.isNotEmpty()) {
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_FAILED, ValidationFailedDTO(order, failedDiscounts))
        } else {
            orderItemsByDiscount.forEach { (discount, orderItems) ->
                discountUsageRepository.upsertDiscountUsage(discount, order.userId, orderItems.sumOf { it.count })
            }
            eventPublisher.publishEvent(DiscountEvents.VALIDATION_SUCCEEDED, ValidationSucceededDTO(order))
        }
    }

    /**
     * Finds the remaining usages for a user for a set of discounts
     * Only returns the remaining usages for discounts that have a maximum number of usages per user
     *
     * @param userId the id of the user
     * @param discounts the discounts to find the remaining usages for
     * @return the remaining usages for each discount that has a maximum number of usages per user
     */
    private suspend fun findRemainingUsagesForDiscounts(
        userId: UUID, discounts: Set<DiscountEntity>
    ): Map<UUID, Long> {
        val discountIds = discounts.filter { it.maxUsagesPerUser != null }.map { it.id!! }.toSet()
        val currentUsagesByDiscount = discountUsageRepository.findByUserIdAndDiscountIdIn(userId, discountIds)
            .associateBy({ it.discountId }) { it.usages }
        return discounts.associate {
            it.id!! to it.maxUsagesPerUser!! - (currentUsagesByDiscount[it.id] ?: 0)
        }
    }

    /**
     * Finds all discounts that are applicable to a user, and a list of product variant and a list of coupons.
     * Also checks if the user can use the discounts with the amount of items.
     *
     * @param input the input for the query
     * @return The applicable discounts for each product variant in order
     * @throws IllegalArgumentException if any of the coupons are not applicable, or multiple coupons are used for the same discount
     */
    suspend fun findApplicableDiscounts(
        input: FindApplicableDiscountsInput
    ): List<DiscountsForProductVariant> {
        val totalStart = System.currentTimeMillis()
        logger.debug("findApplicableDiscounts: userId={}, variantCount={}, couponCount={}",
            input.userId, input.productVariants.size, input.productVariants.sumOf { it.couponIds.size })

        val verifyTime = measureTimeMillis {
            verifyProductVariants(input)
        }
        logger.debug("verifyProductVariants: {}ms", verifyTime)

        val productVariantInputsWithDiscounts = input.productVariants.mapIndexed { i, productVariantInput ->
            val start = System.currentTimeMillis()
            val discounts = findApplicableDiscountsForProductVariant(productVariantInput, input)
            logger.debug("findApplicableDiscountsForProductVariant[{}]: variantId={}, {}ms", i, productVariantInput.productVariantId, System.currentTimeMillis() - start)
            Pair(productVariantInput, discounts)
        }

        val discounts = productVariantInputsWithDiscounts.flatMap { it.second }.toSet()
        val usageStart = System.currentTimeMillis()
        val remainingUsages = findRemainingUsagesForDiscounts(input.userId, discounts).toMutableMap()
        logger.debug("findRemainingUsages: {}ms, discountCount={}", System.currentTimeMillis() - usageStart, discounts.size)

        val couponIds = input.productVariants.flatMap { it.couponIds }.toSet()
        val couponStart = System.currentTimeMillis()
        val couponsById = couponRepository.findAllById(couponIds).collectList().awaitSingle().associateBy { it.id }
        logger.debug("couponRepository.findAllById: {}ms, couponCount={}", System.currentTimeMillis() - couponStart, couponIds.size)

        require(couponIds.all { it in couponsById }) {
            "Coupon(s) with id(s) ${couponIds.filter { it !in couponsById }} do(es) not exist"
        }
        val result = productVariantInputsWithDiscounts.map { (productVariantInput, discounts) ->
            filterApplicableDiscounts(discounts, remainingUsages, productVariantInput, couponsById)
        }
        logger.debug("findApplicableDiscounts total: {}ms, resultCount={}", System.currentTimeMillis() - totalStart, result.size)
        return result
    }

    /**
     * Finds all discounts that are applicable to a user, and a product variant and a list of coupons.
     * Does NOT check if the user can use the discounts with the amount of items.
     *
     * @param productVariantInput defines the product variant, the count, and the coupons to check
     * @param input defines the id of the user for which to check the coupons and the order amount
     * @return The applicable discounts for the product variant, count and user
     */
    private suspend fun findApplicableDiscountsForProductVariant(
        productVariantInput: FindApplicableDiscountsProductVariantInput,
        input: FindApplicableDiscountsInput
    ): List<DiscountEntity> {
        val condition = generateApplicableDiscountsFilterCondition(productVariantInput, input)
        val discounts = repository.query {
            it.select(repository.entityProjection()).from(DiscountEntity.ENTITY)
                .leftJoin(DiscountUsageEntity.ENTITY).on(
                    DiscountEntity.ENTITY.id.eq(DiscountUsageEntity.ENTITY.discountId)
                        .and(DiscountUsageEntity.ENTITY.userId.eq(input.userId))
                ).where(condition)
        }.all().collectList().awaitSingle()
        return discounts
    }

    /**
     * Verifies that the product variants in [input] exist
     *
     * @param input the input for the query
     * @throws IllegalArgumentException if any of the product variants do not exist
     */
    private suspend fun verifyProductVariants(input: FindApplicableDiscountsInput) {
        val productVariantIds = input.productVariants.map { it.productVariantId }.toSet()
        val productVariantsById =
            productVariantRepository.findAllById(productVariantIds).collectList().awaitSingle().associateBy { it.id }
        require(productVariantIds.all { it in productVariantsById }) {
            "Product variant(s) with id(s) ${productVariantIds.filter { it !in productVariantsById }} do(es) not exist"
        }
    }

    /**
     * Returns the [discounts] which can be used with [productVariantInput].
     * Ensures that all coupons are applicable and refer to different discounts.
     * Ensures that the user can use the discount with the amount of items, and updates [remainingUsages] accordingly.
     *
     * @param discounts the discounts to filter
     * @param remainingUsages the remaining usages for each discount
     * @param productVariantInput the input for the query
     * @param couponsById the coupons by id
     * @return The applicable discounts with the associated product variant and count
     */
    private fun filterApplicableDiscounts(
        discounts: List<DiscountEntity>,
        remainingUsages: MutableMap<UUID, Long>,
        productVariantInput: FindApplicableDiscountsProductVariantInput,
        couponsById: Map<UUID?, CouponEntity>
    ): DiscountsForProductVariant {
        val usableDiscounts = discounts.filter { discount ->
            remainingUsages[discount.id]?.let { it >= productVariantInput.count } ?: true
        }
        usableDiscounts.forEach { discount ->
            remainingUsages[discount.id!!] = remainingUsages[discount.id]!! - productVariantInput.count
        }
        val coupons = productVariantInput.couponIds.map { couponsById.getValue(it) }
        val discountsById = usableDiscounts.associateBy { it.id }
        coupons.forEach {
            require(it.discountId in discountsById) {
                "Coupon with id ${it.id} could not be used, either due to insufficient remaining usages, the user not owning the coupon, or the coupon not being applicable"
            }
        }
        coupons.groupBy({ it.discountId }) { it.id }.filter { it.value.size > 1 }.forEach { (discountId, couponsIds) ->
            error("Coupons with ids $couponsIds are used multiple times for the same discount $discountId")
        }
        return DiscountsForProductVariant(
            productVariantInput.productVariantId, productVariantInput.count, usableDiscounts.map { it.toDTO() }
        )
    }

    /**
     * Generates a condition that checks that a discount is applicable to a product variant, a user and a list of coupons.
     *
     * @param productVariantInput defines the product variant, count and coupons to check
     * @param input defines the user id and the order amount to check
     * @return The condition
     */
    private fun generateApplicableDiscountsFilterCondition(
        productVariantInput: FindApplicableDiscountsProductVariantInput, input: FindApplicableDiscountsInput
    ): BooleanExpression? {
        val appliesCondition = generateDiscountAppliesCondition(productVariantInput.productVariantId)
        val noCouponsCondition = generateNoCouponsCondition()
        val couponsCondition = generateUserHasCouponCondition(productVariantInput, input.userId)
        val currentlyValidCondition = generateIsCurrentlyValidCondition()
        val minOrderAmountCondition = generateMinOrderAmountCondition(input.orderAmount)
        val condition = appliesCondition.and(noCouponsCondition.or(couponsCondition)).and(currentlyValidCondition)
            .and(minOrderAmountCondition)
        return condition
    }

    /**
     * Generates a condition that checks that a discount either does not require a min order amount,
     * or the order amount is less than or equal to the provided [amount]
     *
     * @param amount the order amount
     * @return The condition
     */
    private fun generateMinOrderAmountCondition(amount: Int): BooleanExpression? {
        return DiscountEntity.ENTITY.minOrderAmount.isNull.or(
            DiscountEntity.ENTITY.minOrderAmount.loe(amount)
        )
    }

    /**
     * Generates a condition that filters for discounts where any of the coupons in [input] are required
     * and the user has the coupon.
     *
     * @param input defines the user and coupons to check
     * @param userId the id of the user for which to check the coupons
     * @return The condition
     */
    private fun generateUserHasCouponCondition(
        input: FindApplicableDiscountsProductVariantInput, userId: UUID
    ): BooleanExpression {
        return DiscountEntity.ENTITY.id.`in`(
            SQLExpressions.select(CouponEntity.ENTITY.discountId).from(CouponEntity.ENTITY)
                .join(CouponRedemptionEntity.ENTITY)
                .on(CouponRedemptionEntity.ENTITY.couponId.eq(CouponEntity.ENTITY.id)).where(
                    CouponEntity.ENTITY.id.`in`(input.couponIds).and(CouponRedemptionEntity.ENTITY.userId.eq(userId))
                )
        )
    }

    /**
     * Generates a condition that checks that a discount either applies to the product variant, the owning product,
     * or any of the categories of the product.
     * Also checks that either no coupons are required, or the user has a coupon for the discount.
     *
     * @param id the id of the product variant
     * @param authorizedUser the authorized user, or null if the user is not authenticated
     * @return The condition
     */
    fun generateFullDiscountAppliesCondition(id: UUID, authorizedUser: AuthorizedUser?): BooleanExpression {
        val appliesCondition = generateDiscountAppliesCondition(id)
        val hasNoRequiredCouponsCondition = generateNoCouponsCondition()
        val couponsCondition = if (authorizedUser == null) {
            hasNoRequiredCouponsCondition
        } else {
            val filterCondition = CouponEntity.ENTITY.discountId.eq(DiscountEntity.ENTITY.id)
                .and(CouponRedemptionEntity.ENTITY.userId.eq(authorizedUser.id))
            val userHasCouponCondition =
                SQLExpressions.select(Expressions.TRUE).from(CouponEntity.ENTITY).join(CouponRedemptionEntity.ENTITY)
                    .on(CouponEntity.ENTITY.id.eq(CouponRedemptionEntity.ENTITY.couponId)).where(filterCondition)
                    .exists()
            hasNoRequiredCouponsCondition.or(userHasCouponCondition)
        }
        val currentlyValidCondition = generateIsCurrentlyValidCondition()
        val discountAppliesCondition = appliesCondition.and(couponsCondition).and(currentlyValidCondition)
        return discountAppliesCondition
    }

    /**
     * Generates a condition that checks that a discount is currently valid
     *
     * @return The condition
     */
    private fun generateIsCurrentlyValidCondition(): BooleanExpression {
        val now = Expressions.constant(OffsetDateTime.now())
        return DiscountEntity.ENTITY.validFrom.loe(now).and(DiscountEntity.ENTITY.validUntil.goe(now))
    }

    /**
     * Generates a condition that checks that no coupons are required for a discount
     *
     * @return The condition
     */
    private fun generateNoCouponsCondition(): BooleanExpression {
        val hasNoRequiredCouponsCondition = SQLExpressions.select(Expressions.TRUE).from(CouponEntity.ENTITY)
            .where(CouponEntity.ENTITY.discountId.eq(DiscountEntity.ENTITY.id)).notExists()
        return hasNoRequiredCouponsCondition
    }

    /**
     * Generates a condition that checks that a discount either applies to the product variant, the owning product,
     * or any of the categories of the product.
     * Does NOT check for required coupons.
     *
     * @param id the id of the product variant
     * @return The condition
     */
    fun generateDiscountAppliesCondition(id: UUID): BooleanExpression {
        val appliesProductVariantCondition = generateDiscountAppliesToProductVariantCondition(id)
        val appliesProductCondition = generateDiscountAppliesToProductCondition(id)
        val appliesCategoryCondition = generateDiscountAppliesToCategoryCondition(id)
        return appliesProductVariantCondition.or(appliesProductCondition).or(appliesCategoryCondition)
    }

    /**
     * Generates a condition that checks that a discount applies to a product variant
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToProductVariantCondition(id: UUID): BooleanExpression {
        return DiscountEntity.ENTITY.id.`in`(
            SQLExpressions.select(DiscountToProductVariantEntity.ENTITY.discountId)
                .from(DiscountToProductVariantEntity.ENTITY)
                .where(DiscountToProductVariantEntity.ENTITY.productVariantId.eq(id))
        )
    }

    /**
     * Generates a condition that checks that a discount applies to a product
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToProductCondition(id: UUID): BooleanExpression {
        return DiscountEntity.ENTITY.id.`in`(
            SQLExpressions.select(DiscountToProductEntity.ENTITY.discountId).from(DiscountToProductEntity.ENTITY)
                .join(ProductVariantEntity.ENTITY)
                .on(DiscountToProductEntity.ENTITY.productId.eq(ProductVariantEntity.ENTITY.productId))
                .where(ProductVariantEntity.ENTITY.id.eq(id))
        )
    }

    /**
     * Generates a condition that checks that a discount applies to the categories of a product of a product variant
     *
     * @param id the id of the product variant
     * @return The condition
     */
    private fun generateDiscountAppliesToCategoryCondition(id: UUID): BooleanExpression {
        return DiscountEntity.ENTITY.id.`in`(
            SQLExpressions.select(DiscountToCategoryEntity.ENTITY.discountId).from(DiscountToCategoryEntity.ENTITY)
                .join(ProductToCategoryEntity.ENTITY)
                .on(DiscountToCategoryEntity.ENTITY.categoryId.eq(ProductToCategoryEntity.ENTITY.categoryId))
                .join(ProductVariantEntity.ENTITY)
                .on(ProductToCategoryEntity.ENTITY.productId.eq(ProductVariantEntity.ENTITY.productId))
                .where(ProductVariantEntity.ENTITY.id.eq(id))
        )
    }

}