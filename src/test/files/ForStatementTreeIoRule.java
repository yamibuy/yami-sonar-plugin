package com.yamibuy.central.channel.service.temu;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.yamibuy.central.channel.common.CommonService;
import com.yamibuy.central.channel.common.SendWxMsgService;
import com.yamibuy.central.channel.common.TemuApiService;
import com.yamibuy.central.channel.dao.ItemChannelDao;
import com.yamibuy.central.channel.dao.YamiItemDao;
import com.yamibuy.central.channel.entity.YamiChannelItemMapping;
import com.yamibuy.central.channel.entity.YamiItemImage;
import com.yamibuy.central.channel.entity.client.YamiItem;
import com.yamibuy.central.channel.entity.common.ChannelConstant;
import com.yamibuy.central.channel.entity.common.ChannelMessageCode;
import com.yamibuy.central.channel.entity.enums.TemuProductEnum;
import com.yamibuy.central.channel.entity.enums.attribute.temu.AllergenInformation;
import com.yamibuy.central.channel.entity.enums.attribute.temu.OrganicCertified;
import com.yamibuy.central.channel.entity.enums.attribute.tiktok.CountryOfOriginMapping;
import com.yamibuy.central.channel.entity.temu.*;
import com.yamibuy.central.channel.service.ItemChannelService;
import com.yamibuy.central.core.common.JedisClientImp;
import com.yamibuy.central.core.common.YamibuyConstant;
import com.yamibuy.central.core.common.YamibuyException;
import com.yamibuy.central.core.common.YamibuyMessageCode;
import com.yamibuy.central.core.util.ListUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class TemuProductService {

  @Autowired
  public CommonService commonService;

  @Autowired
  public TemuApiService temuApiService;

  @Autowired
  public ItemChannelService itemChannelService;

  @Autowired
  public ItemChannelDao itemChannelDao;

  @Autowired
  public SendWxMsgService sendWxMsgService;

  @Autowired
  public JedisClientImp jedisClientImp;

  @Autowired
  public YamiItemDao yamiItemDao;

  @Value("${inventory_update_threshold:100}")
  public Integer inventory_update_threshold;

  @Value("${temu_inventory_trans_rate:0.5}")
  public Double temu_inventory_trans_rate;

  @Value("${TEMU_CATEGORY_LEVEL_0_LIST:42367}")
  public List<Integer> temu_category_level_0_list;

  @Value("${sync_stock_item_black_list:}")
  public List<String> sync_stock_item_black_list;

  @Value("${repeat_item_list:}")
  public List<String> repeat_item_list;

  public void updateTemuInventory() {

    // 1. 获取TEMU所有店铺的信息
    Map<String, TemuConfig> allTemuConfig = commonService.getAllTemuConfig();
    Set<String> shopIds = allTemuConfig.keySet();

    // 2. 获取店铺下所有的商品信息
    for (String shopId : shopIds) {
      TemuSearchProductRequest searchProductRequest = new TemuSearchProductRequest();
      searchProductRequest.setType(ChannelConstant.TEMU_TYPE_GOODS_LIST_GET);
      searchProductRequest.setPage(1);
      searchProductRequest.setPageSize(100);
      TemuConfig temuConfig =  allTemuConfig.get(shopId);

      List<TemuProduct> temuProducts = new ArrayList<>();
      temuApiService.batchQueryProducts(searchProductRequest, temuConfig, temuProducts);
      log.debug("temuResponse:{}", JSON.toJSONString(temuProducts));

      // 3. 通过对应的item_number,得到亚米001仓库库存
      temuProducts = temuProducts.stream()
        .filter(product -> product.getProductSkuSummaries() != null && product.getProductSkuSummaries()
          .stream()
          .anyMatch(summary -> StringUtils.isNotEmpty(summary.getExtCode())))
        .collect(Collectors.toList());

      // 得到所有item_number
      HashSet<String> itemNumbers = temuProducts.stream()
        .filter(product -> ListUtils.isNotEmpty(product.getProductSkuSummaries()))
        .flatMap(product -> product.getProductSkuSummaries().stream())
        .map(ProductSkuSummaries::getExtCode)
        .filter(StringUtils::isNotEmpty)
        .collect(Collectors.toCollection(HashSet::new));

      if (ListUtils.isNullorEmpty(itemNumbers)){
        log.info("没有找到商品信息");
        continue;
      }

      // todo:
      ArrayList<YamiItem> temuProductInventory = itemChannelDao.queryLAInventoryByItemNumbers(itemNumbers, shopId);

      if (ListUtils.isNullorEmpty(temuProductInventory)){
        log.info("数据库查询库存信息为空");
        continue;
      }

      for (YamiItem yamiItem : temuProductInventory) {
        yamiItem.setQty(Math.min((int) (((double) yamiItem.getQty() / yamiItem.getSold_factor()) * temu_inventory_trans_rate), 999));
      }

      Map<String, YamiItem> temuProductInventoryMap = temuProductInventory.stream()
        .collect(Collectors.toMap(YamiItem::getItem_number, Function.identity(), (exist, current) -> exist));

      // 4. 将需要更新的商品进行更新
      updateInventory(temuConfig, temuProducts, temuProductInventoryMap);
    }
  }

  public void updateInventory(TemuConfig temuConfig, List<TemuProduct> temuProducts, Map<String, YamiItem> temuProductInventoryMap){

    for (TemuProduct temuProduct : temuProducts) {
      try {
        QuantityGetRequest quantityGetRequest = new QuantityGetRequest();
        quantityGetRequest.setProductSkcId(temuProduct.getProductSkcId());
        // todo
        quantityGetRequest.setType(ChannelConstant.TEMU_TYPE_GOODS_QUANTITY_GET);
        JSONObject quantityGetBody = JSON.parseObject(JSON.toJSONString(quantityGetRequest));
        TemuResponse temuResponse = commonService.doTemuPost(temuConfig.getProductConfig(), quantityGetBody);

        if (temuResponse == null || !temuResponse.getSuccess() || temuResponse.getResult() == null || ListUtils.isNullorEmpty(temuResponse.getResult().getProductSkuStockList())) {
          continue;
        }

        TemuResult temuResult = temuResponse.getResult();
        List<SkuStockChange> skuStockChanges = new ArrayList<>();

        for (ProductSkuStock productSkuStock : temuResult.getProductSkuStockList()) {

          // todo
          String itemNumber = temuProduct.getProductSkuSummaries().stream()
            .filter(productSkuSummary -> productSkuSummary.getProductSkuId().equals(productSkuStock.getProductSkuId()))
            .map(ProductSkuSummaries::getExtCode)
            .findFirst()
            .orElse(null);

          if (StringUtils.isNotEmpty(itemNumber) && !sync_stock_item_black_list.contains(itemNumber)) {
            Integer temuQuality = productSkuStock.getSkuStockQuantity();
            YamiItem yamiItem = temuProductInventoryMap.get(itemNumber);
            Integer yamiItemQty = yamiItem != null ? yamiItem.getQty() : null;
            if (yamiItemQty != null && (yamiItemQty < inventory_update_threshold || temuQuality < inventory_update_threshold) && !yamiItemQty.equals(temuQuality)) {
              SkuStockChange skuStockChange = new SkuStockChange();
              skuStockChange.setProductSkuId(productSkuStock.getProductSkuId());
              skuStockChange.setStockDiff(yamiItemQty - temuQuality);
              skuStockChanges.add(skuStockChange);
            }
          }
        }
        updateInventory4Group(skuStockChanges, temuProduct, temuConfig, temuResult.getProductSkuStockList(), 0);

      } catch (Exception exception) {
        exception.printStackTrace();
        log.info(ChannelConstant.MSG_UPDATE_STOCK_ERROR, temuConfig.getShop_id(), temuProduct.getProductSkcId());
      }
    }
  }

  public void updateInventory4Group(List<SkuStockChange> skuStockChanges, TemuProduct temuProduct, TemuConfig temuConfig, List<ProductSkuStock> productSkuStockList, Integer retryNum){
    if (ListUtils.isNotEmpty(skuStockChanges)){
      QuantityUpdate quantityUpdate = new QuantityUpdate();
      quantityUpdate.setSkuStockChangeList(skuStockChanges);
      quantityUpdate.setProductSkcId(temuProduct.getProductSkcId());
      quantityUpdate.setType(ChannelConstant.TEMU_TYPE_GOODS_QUANTITY_UPDATE);
      TemuResponse updateInventoryResponse = commonService.doTemuPost(temuConfig.getProductConfig(), JSON.parseObject(JSON.toJSONString(quantityUpdate)));

      if (updateInventoryResponse != null && updateInventoryResponse.getSuccess()){
        log.info("商品库存更新成功");
      } else if (updateInventoryResponse != null && updateInventoryResponse.getErrorMsg() != null && updateInventoryResponse.getErrorMsg().contains("设置库存数量最大上限")) {
        log.info("temu后台设置了最大库存");
        String errorMsg = updateInventoryResponse.getErrorMsg();
        int index = errorMsg.lastIndexOf("为");
        if(index != -1) {
          String result = errorMsg.substring(index + 1);
          int limitNum = Integer.parseInt(result);
          boolean changeStock = false;
          for(SkuStockChange change : skuStockChanges) {
            ProductSkuStock productSkuStock = productSkuStockList.stream().filter(p -> p.getProductSkuId().equals(change.getProductSkuId())).findFirst().orElse(null);
            if(null != productSkuStock) {
              Integer temuStock = productSkuStock.getSkuStockQuantity();
              change.setStockDiff(limitNum - temuStock);
              changeStock = true;
            }
          }
          if (changeStock && retryNum < 3) {
            updateInventory4Group(skuStockChanges, temuProduct, temuConfig, productSkuStockList, ++retryNum);
          }
        }
      } else if (updateInventoryResponse != null){
        sendWxMsgService.sendWxMsg(true, ChannelConstant.MSG_SYN_STOCK_ERROR, String.valueOf(temuProduct.getProductSkcId()), temuConfig.getShop_id(), JSON.toJSONString(updateInventoryResponse.getErrorMsg()), ChannelConstant.CHANNEL_TEMU);
      } else {
        sendWxMsgService.sendWxMsg(true, ChannelConstant.MSG_SYN_STOCK_ERROR, String.valueOf(temuProduct.getProductSkcId()), temuConfig.getShop_id(), "同步失败", ChannelConstant.CHANNEL_TEMU);
      }
    }
  }

  public void syncTemuProduct() {
    Map<String, TemuConfig> temuConfigMap = commonService.getAllTemuConfig();
    if (CollectionUtils.isEmpty(temuConfigMap)) {
      return;
    }
    List<YamiChannelItemMapping> needUpdateItemList = new ArrayList<>();
    List<YamiChannelItemMapping> needInsertItemList = new ArrayList<>();
    List<YamiChannelItemMapping> allTemuItemMappingList = new ArrayList<>();
    List<String> shopIdList = new ArrayList<>();
    temuConfigMap.values().forEach(temuConfig -> {
      shopIdList.add(temuConfig.getShop_id());
      TemuSearchProductRequest searchProductRequest = new TemuSearchProductRequest();
      searchProductRequest.setType(ChannelConstant.TEMU_TYPE_GOODS_LIST_GET);
      searchProductRequest.setPage(1);
      searchProductRequest.setPageSize(100);

      //使用temu接口查询所有商品信息
      List<TemuProduct> temuProductList = new ArrayList<>();
      temuApiService.batchQueryProducts(searchProductRequest, temuConfig, temuProductList);
      List<YamiChannelItemMapping> yamiChannelItemMappings = mappingTemuProduct(temuProductList, temuConfig);
      allTemuItemMappingList.addAll(yamiChannelItemMappings);
    });
    //查询当前所有店铺的商品信息
    List<YamiChannelItemMapping> channelItemList = itemChannelService.getAllChannelItem(shopIdList);
    Map<String, YamiChannelItemMapping> channelItemMap = channelItemList.stream().collect(Collectors.toMap(i -> i.getShop_id() + "_" + i.getProduct_id() + "_" + i.getSku_id(), Function.identity(), (o, n) -> n));
    for (YamiChannelItemMapping item : allTemuItemMappingList) {
      YamiChannelItemMapping mapping = channelItemMap.get(item.getShop_id() + "_" + item.getProduct_id() + "_" + item.getSku_id());
      if (null != mapping) {
        if (!item.getItem_number().equals(mapping.getItem_number()) || !item.getSold_factor().equals(mapping.getSold_factor())) {
          //数量被修改通知
          if (!item.getSold_factor().equals(mapping.getSold_factor())) {
            log.info("商品数量被修改,product_id:{},sku_id:{},item_number:{},before_sold_factor:{},after_sold_factor:{}", item.getProduct_id(), item.getSku_id(), item.getItem_number(), mapping.getSold_factor(),item.getSold_factor());
            sendWxMsgService.sendWxMsg(true,"商品数量被修改",item.getProduct_id() + "_" + item.getSku_id() + "_" + item.getItem_number(), item.getShop_id(), "商品数量被修改", ChannelConstant.CHANNEL_TEMU);
          }
          item.setEdit_user("channel-job");
          needUpdateItemList.add(item);
        }
      } else {
        needInsertItemList.add(item);
      }
    }
    //批量更新/插入
    if (needUpdateItemList.size() > 0) itemChannelDao.updateChannelItem(needUpdateItemList);
    if (needInsertItemList.size() > 0) itemChannelDao.batchInsertChannelItemMapping(needInsertItemList);
  }

  public List<YamiChannelItemMapping> mappingTemuProduct(List<TemuProduct> temuProductList, TemuConfig temuConfig) {
    ArrayList<YamiChannelItemMapping> yamiChannelItemMappings = new ArrayList<>();
    for (TemuProduct temuProduct : temuProductList) {
      if (ListUtils.isNullorEmpty(temuProduct.getProductSkuSummaries())) {
        log.info("当前product:{}中sku为空", temuProduct.getProductId());
        continue;
      }
      for (ProductSkuSummaries sku : temuProduct.getProductSkuSummaries()) {
        if (StringUtils.isEmpty(sku.getExtCode())) {
          log.info("当前sku未设置yami商品编号:{}", sku.getProductSkuId());
          continue;
        }

        if(sku.getExtCode().length() > 15) {
          continue;
        }
        YamiChannelItemMapping mapping = new YamiChannelItemMapping();
        mapping.setProduct_id(String.valueOf(temuProduct.getProductId()));
        mapping.setSku_id(String.valueOf(sku.getProductSkuId()));
        mapping.setItem_number(sku.getExtCode());
        mapping.setIn_user("channel-job");
        mapping.setShop_id(temuConfig.getShop_id());

        // 设置sold_factory
        mapping.setSold_factor(setSoldFactory(sku,String.valueOf(temuProduct.getProductSkcId()),temuConfig));
        yamiChannelItemMappings.add(mapping);
      }
    }
    return yamiChannelItemMappings;
  }

  private Integer setSoldFactory(ProductSkuSummaries sku, String skcId, TemuConfig temuConfig) {
    int soldFactory = 1;
    List<ProductSkuSpecList> productSkuSpecList = sku.getProductSkuSpecList();
    for (ProductSkuSpecList skuSpecList : productSkuSpecList) {
      if ("数量".equalsIgnoreCase(skuSpecList.getParentSpecName())){
        try {
          soldFactory = Integer.parseInt(skuSpecList.getSpecName());
        } catch (NumberFormatException e) {
          sendWxMsgService.sendWxMsg(true, "同步商品数量出错", skcId, temuConfig.getShop_id(), "sold_factory解析失败", ChannelConstant.CHANNEL_TEMU);
          log.error("数量属性转换为sold_factor失败!");
          e.printStackTrace();
        }
      }
    }
    return soldFactory;
  }

  public void syncInventory() {
    try {
      updateTemuInventory();
    } catch (Exception exception) {
      log.error("更新temu商品库存失败");
      exception.printStackTrace();
    }
  }

  public void batchCreateTemuProduct(MultipartFile file, String shop_id) {

    try {
      // 1. 解析Excel表格数据
      List<UploadTemuItemInfo> rawList = parseExcel(file);
      log.info("rawList=>{}", JSON.toJSONString(rawList));
      if(ListUtils.isNullorEmpty(rawList)) {
        throw new YamibuyException("The Excel doesn't have data", ChannelMessageCode.THE_EXCEL_NOT_HAVE_DATA.getCode());
      }

      // 2. 对商品信息进行处理
      List<String> itemNumberList = rawList.stream()
        .map(UploadTemuItemInfo::getItem_number)
        .collect(Collectors.toList());

      if(ListUtils.isNullorEmpty(itemNumberList)) {
        throw new YamibuyException("Excel中item_number为空", ChannelMessageCode.THE_EXCEL_ITEM_NUMBER_IS_NULL.getCode());
      }

      // 3. 当前shop_id已经存在的active商品禁止创建
      List<String> existItemNumbers = yamiItemDao.getAllExistProductByItemNumbers(itemNumberList, shop_id, false);
      existItemNumbers.removeIf(item-> repeat_item_list.contains(item));
      for (String existItemNumber : existItemNumbers) {
        sendWxMsgService.sendWxMsg(true, "temu商品创建失败", existItemNumber, shop_id, "商品item_number在当前店铺中已存在", ChannelConstant.CHANNEL_TEMU);
      }
      itemNumberList.removeAll(existItemNumbers);
      rawList.removeIf(item -> existItemNumbers.contains(item.getItem_number()));
      if(ListUtils.isNullorEmpty(itemNumberList)) {
        throw new YamibuyException("当前创建的所有商品都已存在", ChannelMessageCode.THIS_PRODUCT_ALREADY_EXISTS.getCode());
      }

      // 查询得到所需要的结果
      List<TemuProductMapping> temuProductInfoList = yamiItemDao.queryItemInfoByItemNumber4Temu(itemNumberList);
      Map<String, TemuProductMapping> temuProductInfoMap = temuProductInfoList.stream()
        .collect(Collectors.toMap(TemuProductMapping::getItem_number, Function.identity(), (o, n) -> n));


      // 4. 创建商品
      for (UploadTemuItemInfo uploadTemuItemInfo : rawList) {
        if (temuProductInfoMap.containsKey(uploadTemuItemInfo.getItem_number())) {
          String createResult = createProduct(uploadTemuItemInfo, shop_id, temuProductInfoMap.get(uploadTemuItemInfo.getItem_number()));
          if (!YamibuyConstant.RESPONSE_SUCCESS.equals(createResult)) {
            sendWxMsgService.sendWxMsg(true, "temu商品创建失败", uploadTemuItemInfo.getItem_number(), shop_id, createResult, ChannelConstant.CHANNEL_TEMU);
          }
        }
      }
    } catch (Exception exception) {
      if (exception instanceof YamibuyException) {
        throw exception;
      }
      exception.printStackTrace();
      log.error("创建商品过程中异常");
      throw new YamibuyException("创建商品过程中异常", YamibuyMessageCode.ERROR_REQUESTMESSAGE.getCode());
    }
  }

  private String createProduct(UploadTemuItemInfo uploadTemuItemInfo, String shop_id, TemuProductMapping temuProductMapping) {

    try {
      if (jedisClientImp.exists(String.format("temu:created:%s", uploadTemuItemInfo.getItem_number()))) {
        return "商品item_number在当前店铺中已存在";
      }

      if (Objects.isNull(temuProductMapping.getLen())
        || Objects.isNull(temuProductMapping.getHeight())
        || Objects.isNull(temuProductMapping.getWidth())
        || StringUtils.isEmpty(temuProductMapping.getBrand_name())) {
        log.info("temuProductMapping=>{}", JSON.toJSONString(temuProductMapping));
        return "商品数据库信息获取失败";
      }

      TemuProduct temuProduct = new TemuProduct();
      TemuConfig temuConfig = commonService.getTemuConfigByShopId(shop_id);
      if (null == temuConfig) {
        throw new YamibuyException("获取temu店铺配置失败", ChannelMessageCode.FAILED_TO_GET_CONFIGURATION.getCode());
      }

      Integer maxCatId = matchCategory(temuProduct, uploadTemuItemInfo.getCatName(), temuConfig);

      // productName:货品名称
      String titleEn = uploadTemuItemInfo.getTitle_en();
      String brandName = temuProductMapping.getBrand_name();
      Matcher matcher = Pattern.compile("\\[.*?Packs]").matcher(titleEn);
      if (matcher.find()) {
        titleEn = titleEn.replace(matcher.group(), matcher.group() + " " + brandName);
      } else {
        titleEn = brandName + ", " + titleEn;
      }

      temuProduct.setProductName(uploadTemuItemInfo.getTitle());
      // productI18nReqs:多语言标题设置
      ProductI18nReq productI18nReq = new ProductI18nReq();
      productI18nReq.setLanguage("en");
      productI18nReq.setProductName(titleEn);
      temuProduct.setProductI18nReqs(Collections.singletonList(productI18nReq));

      // productWarehouseRouteReq
      TargetRoute targetRoute = new TargetRoute();
      targetRoute.setWarehouseId("WH-05956948705431402");
      targetRoute.setSiteIdList(Collections.singletonList(100));
      ProductWarehouseRouteReq productWarehouseRouteReq = new ProductWarehouseRouteReq();
      productWarehouseRouteReq.setTargetRouteList(Collections.singletonList(targetRoute));
      temuProduct.setProductWarehouseRouteReq(productWarehouseRouteReq);

      // productPropertyReqs:货品属性
      setProductPropertyReqs(temuProduct, temuConfig, uploadTemuItemInfo, temuProductMapping, maxCatId);

      List<String> carouselImageUrls = setCarouselImageUrls(temuProductMapping.getImages(), temuConfig, maxCatId);
      temuProduct.setCarouselImageUrls(carouselImageUrls);
      setProductSpecPropertyReqs(temuProduct, uploadTemuItemInfo, temuConfig);
      setProductSkcReqs(temuProduct, uploadTemuItemInfo, temuProductMapping);
      temuProduct.setMaterialImgUrl(carouselImageUrls.get(0));
      temuProduct.setGoodsLayerDecorationReqs(new ArrayList<>());
      temuProduct.setSizeTemplateIds(new ArrayList<>());
      temuProduct.setShowSizeTemplateIds(new ArrayList<>());

      setProductWhExtAttrReq(temuProduct, uploadTemuItemInfo, temuProductMapping);

      // productOuterPackageReq:外包装图片

      List<ProductOuterPackageImageReq> outerPackageImageReqs = new ArrayList<>();
      if (CollectionUtils.isEmpty(temuProductMapping.getWarehouseImages())) {
        ProductOuterPackageImageReq outerPackageImageReq = new ProductOuterPackageImageReq();
        outerPackageImageReq.setImageUrl(carouselImageUrls.get(0));
        outerPackageImageReqs.add(outerPackageImageReq);
      } else {
        List<String> yamiWarehouseImages = temuProductMapping.getWarehouseImages().stream().map(YamiItemImage::getImage_url).collect(Collectors.toList());
        List<String> imageUrls = uploadTemuImage(yamiWarehouseImages, TemuProductEnum.ImageBizType.OUTER_PACKAGE.getCode(), temuConfig, maxCatId);
        for (String imageUrl : imageUrls) {
          ProductOuterPackageImageReq outerPackageImageReq = new ProductOuterPackageImageReq();
          outerPackageImageReq.setImageUrl(imageUrl);
          outerPackageImageReqs.add(outerPackageImageReq);
        }
      }
      temuProduct.setProductOuterPackageImageReqs(outerPackageImageReqs.stream().limit(6).collect(Collectors.toList()));

      // productOuterPackageReq:货品外包装信息
      ProductOuterPackageReq packageReq = new ProductOuterPackageReq();
      packageReq.setPackageShape(1);
      packageReq.setPackageType(TemuProductEnum.SkuPackageType.querypackageType(uploadTemuItemInfo.getPackageType()));
      temuProduct.setProductOuterPackageReq(packageReq);

      // productNonAuditExtAttrReq
      temuProduct.setProductNonAuditExtAttrReq(new ProductNonAuditExtAttrReq());

      // productShipmentReq:半托管货品配送信息请求
      ProductShipmentReq productShipmentReq = new ProductShipmentReq();
      productShipmentReq.setFreightTemplateId("HFT-1042150323855371402");
      productShipmentReq.setShipmentLimitSecond(172800);
      temuProduct.setProductShipmentReq(productShipmentReq);

      // productSemiManagedReq:半托管相关信息
      ProductSemiManagedReq semiManagedReq = new ProductSemiManagedReq();
      ArrayList<Integer> semiManagedList = new ArrayList<>();
      semiManagedList.add(100);
      semiManagedReq.setBindSiteIds(semiManagedList);
      temuProduct.setProductSemiManagedReq(semiManagedReq);

      log.debug("temuProduct=>{}", JSON.toJSONString(temuProduct));

      // 商品创建
      return createTemuProduct(temuProduct, temuConfig, 0, uploadTemuItemInfo.getItem_number());

    } catch (YamibuyException exception){
      log.error(JSON.toJSONString(exception.getResponse().getBody()));
      return String.valueOf(exception.getResponse().getBody());
    } catch (Exception e) {
      e.printStackTrace();
      return "创建失败";
    }
  }

  private List<String> setCarouselImageUrls(List<YamiItemImage> images,TemuConfig temuConfig,Integer maxCatId) {
    // carouselImageUrls:轮播图
    List<String> yamiCarouselImgs = new ArrayList<>(Collections.nCopies(1,""));
    for (YamiItemImage image : images) {
      if ("Y".equals(image.getIs_primary())) {
        yamiCarouselImgs.set(0, image.getImage_url());
        continue;
      }
      if (yamiCarouselImgs.size() < 10) {
        yamiCarouselImgs.add(image.getImage_url());
      }

    }
    List<String> carouselImageUrls = new ArrayList<>(uploadTemuImage(yamiCarouselImgs, TemuProductEnum.ImageBizType.DEFAULT.getCode(), temuConfig, maxCatId));
    if (carouselImageUrls.size() < 3) {
      throw new YamibuyException("图片小于3张", YamibuyMessageCode.ERROR_REQUESTMESSAGE.getCode());
    }
    return carouselImageUrls;
  }

  public String createTemuProduct(TemuProduct temuProduct, TemuConfig temuConfig, Integer count, String itemNumber) {
    count++;
    TemuResponse response = temuApiService.createProduct(temuProduct, temuConfig);
    if (response == null || count > 7) {
      return "创建失败";
    } else {
      if (response.getSuccess()) {
        String key = String.format("temu:created:%s", itemNumber);
        jedisClientImp.set(key, "1");
        jedisClientImp.expire(key, 86400);
        return YamibuyConstant.RESPONSE_SUCCESS;
      } else if (response.getErrorCode() == 2000018) {
        Pattern pattern = Pattern.compile("/product/open/.+?/([\\w-]+)-goods");
        Matcher matcher = pattern.matcher(response.getErrorMsg());
        if (matcher.find()) {
          temuProduct.getCarouselImageUrls().removeIf(s -> s.contains(matcher.group()));
          temuProduct.getProductOuterPackageImageReqs().removeIf(s -> s.getImageUrl().contains(matcher.group()));
          return createTemuProduct(temuProduct, temuConfig, count, itemNumber);
        }
      }
    }
    return "创建失败！";
  }

  /**
   * 设置商品规格属性
   *
   * @param temuProduct
   * @param uploadTemuItemInfo
   * @param temuConfig
   */
  public void setProductSpecPropertyReqs(TemuProduct temuProduct, UploadTemuItemInfo uploadTemuItemInfo, TemuConfig temuConfig) {
    ProductSpecPropertyReq specPropertyReq = new ProductSpecPropertyReq();
    String parentSpecName = uploadTemuItemInfo.getSpecName();
    String specValue = uploadTemuItemInfo.getSpecValue();
    Integer parentSpecId = TemuProductEnum.ParentSpec.queryParentSpecId(parentSpecName);
    specPropertyReq.setParentSpecId(parentSpecId);
    specPropertyReq.setParentSpecName(parentSpecName);
    specPropertyReq.setSpecId(createSpecId(parentSpecId, specValue, temuConfig));
    specPropertyReq.setSpecName(specValue);
    specPropertyReq.setPid(0);
    specPropertyReq.setRefPid(0);
    specPropertyReq.setPropName(parentSpecName);
    specPropertyReq.setVid(0);
    specPropertyReq.setPropValue(specValue);
    specPropertyReq.setValueUnit("");
    specPropertyReq.setValueGroupId(0);
    specPropertyReq.setValueGroupName("");
    specPropertyReq.setValueExtendInfo("");
    specPropertyReq.setTemplatePid(0);
    temuProduct.setProductSpecPropertyReqs(Collections.singletonList(specPropertyReq));
  }

  /**
   * 创建规格
   *
   * @param parentSpecId 父规格
   * @param specValue    规格名称
   * @param temuConfig   配置
   * @return 规格id
   */
  private Integer createSpecId(Integer parentSpecId, String specValue, TemuConfig temuConfig) {
    if (TemuProductEnum.ParentSpec.QUANTITY.getParentSpecId().equals(parentSpecId)) {
      if (!StringUtils.isNumeric(specValue) || Integer.parseInt(specValue) < 0) {
        throw new YamibuyException("商品数量必须为正整数", ChannelMessageCode.PRODUCT_QUANTITY_FORMAT_IS_INCORRECT.getCode());
      }
      if (Integer.parseInt(specValue) < 20) {
        return TemuProductEnum.QtySpec.querySpecId(specValue);
      }
    }
    Integer specId = temuApiService.createSpec(parentSpecId, specValue, temuConfig);
    if (specId == null) {
      throw new YamibuyException("商品规格获取失败", ChannelMessageCode.FAILED_TO_GET_PRODUCT_SPEC.getCode());
    } else {
      return specId;
    }
  }

  /**
   * productPropertyReqs:货品属性
   *
   * @param temuProduct
   * @param temuConfig
   * @param uploadTemuItemInfo
   * @param temuProductInfo
   * @param maxCatId
   */
  public void setProductPropertyReqs(TemuProduct temuProduct, TemuConfig temuConfig, UploadTemuItemInfo
    uploadTemuItemInfo, TemuProductMapping temuProductInfo, Integer maxCatId) {

    List<TemuProperty> temuProperties;

    temuProperties = temuApiService.getGoodsAttrs(maxCatId, temuConfig);
    if (ListUtils.isNullorEmpty(temuProperties)) {
      throw new YamibuyException("获取分类下属性失败", YamibuyMessageCode.ERROR_REQUESTMESSAGE.getCode());
    }
    temuProperties = temuProperties.stream().filter(p -> p.getRequired() || "净含量".equals(p.getName()) || "商品重量".equals(p.getName())).collect(Collectors.toList());

    ArrayList<ProductPropertyReq> temuPropertyReqs = new ArrayList<>();
    for (TemuProperty temuProperty : temuProperties) {
      String propertyName = temuProperty.getName();
      if ("净含量".equals(propertyName) && StringUtils.isEmpty(uploadTemuItemInfo.getItem_weight())) {
        // 表格上传中有item_weight净含量时才上传
        continue;
      }
      ProductPropertyReq propertyReq = new ProductPropertyReq();
      propertyReq.setTemplatePid(temuProperty.getTemplatePid());
      propertyReq.setPid(temuProperty.getPid());
      propertyReq.setRefPid(temuProperty.getRefPid());
      propertyReq.setPropName(propertyName);
      propertyReq.setNumberInputValue("");
      propertyReq.setValueExtendInfo("");
      if ("保质期".equals(propertyName)) {
        Integer expireTime = temuProductInfo.getExpire_dtm();
        Integer shelfLife = temuProductInfo.getShelf_life();
        if (shelfLife == null) {
          propertyReq.setPropValue("5");
          propertyReq.setValueUnit("年");
        } else if (shelfLife > 365) {
          int shelfMonth = shelfLife / 30;
          propertyReq.setPropValue(String.valueOf(shelfMonth));
          propertyReq.setValueUnit("月");
        } else if (shelfLife == -1) {
          Integer shelf = (int) (expireTime - System.currentTimeMillis() / 1000) /(30 * 86400);
          if (shelf == 0) {
            propertyReq.setPropValue(String.valueOf((int) ((expireTime - System.currentTimeMillis() / 1000) /86400)));
            propertyReq.setValueUnit("天");
          }else{
            propertyReq.setPropValue(String.valueOf(shelf));
            propertyReq.setValueUnit("月");
          }
        } else {
          propertyReq.setPropValue(String.valueOf(shelfLife));
          propertyReq.setValueUnit("天");
        }
        //vid 是配合有固定值的时候使用
        propertyReq.setVid(0);
      } else if ("过敏原信息".equals(propertyName)) {
        String allergenInfo = uploadTemuItemInfo.getAllergen_information().replaceAll("，",",");
        String[] allergenInfos = allergenInfo.split(",");
        for (String info : allergenInfos) {
          AllergenInformation allergenInformation = AllergenInformation.findByValue(info);
          if (allergenInformation == null) {
            throw new YamibuyException("没有找到过敏原对应信息", YamibuyMessageCode.ERROR_REQUESTMESSAGE.getCode());
          }
          ProductPropertyReq allergenPropertyReq = new ProductPropertyReq();
          allergenPropertyReq.setTemplatePid(temuProperty.getTemplatePid());
          allergenPropertyReq.setPid(temuProperty.getPid());
          allergenPropertyReq.setRefPid(temuProperty.getRefPid());
          allergenPropertyReq.setPropName(propertyName);
          allergenPropertyReq.setNumberInputValue("");
          allergenPropertyReq.setValueExtendInfo("");
          allergenPropertyReq.setPropValue(allergenInformation.getValue());
          allergenPropertyReq.setVid(allergenInformation.getVid());
          allergenPropertyReq.setValueUnit("");
          temuPropertyReqs.add(allergenPropertyReq);
        }
        continue;
      } else if ("品牌名称".equals(propertyName)) {
        String brandName = temuProductInfo.getBrand_name();
        propertyReq.setPropValue(brandName.replaceAll("\\s", ""));
        propertyReq.setVid(0);
        propertyReq.setValueUnit("");
      } else if ("美国农业部有机认证".equals(propertyName)) {
        propertyReq.setPropValue(OrganicCertified.LESS_THAN_70.getValue());
        propertyReq.setVid(OrganicCertified.LESS_THAN_70.getVid());
        propertyReq.setValueUnit("");
      } else if ("净含量".equals(propertyName)) {
        propertyReq.setPropValue(uploadTemuItemInfo.getItem_weight());
        propertyReq.setVid(0);
        propertyReq.setValueUnit(TemuProductEnum.NetContentUnitCode.Gram.getName());
      } else if ("商品重量".equals(propertyName)) {
        propertyReq.setPropValue(uploadTemuItemInfo.getPackage_weight());
        propertyReq.setVid(0);
        propertyReq.setValueUnit(TemuProductEnum.NetContentUnitCode.Gram.getName());
      }
      temuPropertyReqs.add(propertyReq);
    }

    temuProduct.setProductPropertyReqs(temuPropertyReqs);
  }

  /**
   * productWhExtAttrReq:货品仓配供应链侧扩展属性请求
   *
   * @param temuProduct
   * @param uploadTemuItemInfo
   */
  public void setProductWhExtAttrReq(TemuProduct temuProduct, UploadTemuItemInfo
    uploadTemuItemInfo, TemuProductMapping temuProductMapping) {
    String country = temuProductMapping.getCountry();
    if ("United States".equals(country)) {
      country ="US";
    }else if ("Taiwan".equals(country) || "Hong Kong".equals(country)) {
      country = "China";
    }
    CountryOfOriginMapping countryOfOriginMapping = CountryOfOriginMapping.valueOf(country);
    ProductWhExtAttrReq productWhExtAttrReq = new ProductWhExtAttrReq();
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("countryShortName", countryOfOriginMapping.getCountryShortName());
    productWhExtAttrReq.setProductOrigin(jsonObject);
    productWhExtAttrReq.setOuterGoodsUrl("https://www.yamibuy.com/zh/p/item/" + uploadTemuItemInfo.getItem_number());
    temuProduct.setProductWhExtAttrReq(productWhExtAttrReq);
  }

  /**
   * set货品skc列表
   *
   * @param temuProduct
   * @param uploadTemuItemInfo
   * @param temuProductMapping
   */
  private void setProductSkcReqs(TemuProduct temuProduct, UploadTemuItemInfo
    uploadTemuItemInfo, TemuProductMapping temuProductMapping) {

    ProductSkcReq productSkcReq = new ProductSkcReq();

    // previewImgUrls
    productSkcReq.setPreviewImgUrls(Collections.singletonList(temuProduct.getCarouselImageUrls().get(0)));

    // UPC
    List<String> upcs = temuProductMapping.getUpcs();
    String upc = upcs.stream()
      .filter(u -> !u.equals(temuProductMapping.getItem_number()))
      .findFirst()
      .orElseGet(() -> upcs.get(0));
    productSkcReq.setExtCode(upc);

    // mainProductSkuSpecReqs
    ProductSkuSpecReq productSkuSpecReq = new ProductSkuSpecReq();
    productSkuSpecReq.setParentSpecId(0);
    productSkuSpecReq.setParentSpecName("");
    productSkuSpecReq.setSpecId(0);
    productSkuSpecReq.setSpecName("");
    productSkcReq.setMainProductSkuSpecReqs(Collections.singletonList(productSkuSpecReq));

    // productSkuReqs
    ArrayList<ProductSkuReq> productSkuReqs = setProductSkuReqsInfo(temuProduct, uploadTemuItemInfo, temuProductMapping);
    productSkcReq.setProductSkuReqs(productSkuReqs);

    temuProduct.setProductSkcReqs(Collections.singletonList(productSkcReq));

  }

  public ArrayList<ProductSkuReq> setProductSkuReqsInfo(TemuProduct temuProduct, UploadTemuItemInfo
    uploadTemuItemInfo, TemuProductMapping temuProductMapping) {

    // productSkuReqs
    ProductSkuReq productSkuReq = new ProductSkuReq();

    // thumbUrl
    productSkuReq.setThumbUrl(temuProduct.getCarouselImageUrls().get(0));

    // productSkuThumbUrlI18nReqs
    productSkuReq.setProductSkuThumbUrlI18nReqs(new ArrayList<>());

    // extCode
    productSkuReq.setExtCode(uploadTemuItemInfo.getItem_number());

    // productSkuStockQuantityReq
    ProductSkuStockQuantityReq productSkuStockQuantityReq = new ProductSkuStockQuantityReq();
    WarehouseStockQuantityReq warehouseStockQuantityReq = new WarehouseStockQuantityReq();
    warehouseStockQuantityReq.setWarehouseId("WH-05956948705431402");
    warehouseStockQuantityReq.setTargetStockAvailable(1);
    productSkuStockQuantityReq.setWarehouseStockQuantityReqs(Collections.singletonList(warehouseStockQuantityReq));
    productSkuReq.setProductSkuStockQuantityReq(productSkuStockQuantityReq);

    // supplierPrice
    productSkuReq.setSupplierPrice((int) Math.round(uploadTemuItemInfo.getPrice() * 100));

    // currencyType
    productSkuReq.setCurrencyType("USD");

    // productSkuSpecReqs
    ProductSkuSpecReq productSkuSpecReq = new ProductSkuSpecReq();
    ProductSpecPropertyReq specPropertyReq = temuProduct.getProductSpecPropertyReqs().get(0);
    productSkuSpecReq.setParentSpecId(specPropertyReq.getParentSpecId());
    productSkuSpecReq.setParentSpecName(specPropertyReq.getParentSpecName());
    productSkuSpecReq.setSpecId(specPropertyReq.getSpecId());
    productSkuSpecReq.setSpecName(specPropertyReq.getSpecName());
    productSkuReq.setProductSkuSpecReqs(Collections.singletonList(productSkuSpecReq));

    // productSkuWhExtAttrReq
    ProductSkuWhExtAttrReq whExtAttrReq = new ProductSkuWhExtAttrReq();
    // 货品sku体积productSkuVolumeReq
    ProductSkuVolumeReq productSkuVolumeReq = new ProductSkuVolumeReq();
    List<Double> volume = Arrays.asList(temuProductMapping.getLen(), temuProductMapping.getWidth(), temuProductMapping.getHeight());
    volume.sort(Comparator.reverseOrder());
    productSkuVolumeReq.setLen((int) Math.round(volume.get(0) * 25.4));
    productSkuVolumeReq.setWidth((int) Math.round(volume.get(1) * 25.4));
    productSkuVolumeReq.setHeight((int) Math.round(volume.get(2) * 25.4));
    whExtAttrReq.setProductSkuVolumeReq(productSkuVolumeReq);
    // productSkuBarCodeReqs
    whExtAttrReq.setProductSkuBarCodeReqs(new ArrayList<>());
    // productSkuSensitiveLimitReq
    whExtAttrReq.setProductSkuSensitiveLimitReq(new ProductSkuSensitiveLimitReq());
    // 货品sku重量productSkuWeightReq
    ProductSkuWeightReq skuWeightReq = new ProductSkuWeightReq();
    skuWeightReq.setValue((int) (Double.parseDouble(uploadTemuItemInfo.getPackage_weight()) * 1000));
    whExtAttrReq.setProductSkuWeightReq(skuWeightReq);

    // productSkuMultiPackReq
    ProductSkuMultiPackReq multiPackReq = new ProductSkuMultiPackReq();
    multiPackReq.setNumberOfPieces(1);
    ProductSkuNetContentReq skuNetContentReq = new ProductSkuNetContentReq();
    skuNetContentReq.setNetContentUnitCode(TemuProductEnum.NetContentUnitCode.Gram.getCode());
    skuNetContentReq.setNetContentNumber((int) Double.parseDouble(uploadTemuItemInfo.getPackage_weight()));
    multiPackReq.setProductSkuNetContentReq(skuNetContentReq);
    multiPackReq.setSkuClassification(TemuProductEnum.SkuClassification.SINGLE.getCode());
    multiPackReq.setPieceUnitCode(TemuProductEnum.PieceUnitCode.PACKAGE.getCode());
    productSkuReq.setProductSkuMultiPackReq(multiPackReq);

    // 货品sku敏感属性请求
    ProductSkuSensitiveAttrReq sensitiveAttrReq = new ProductSkuSensitiveAttrReq();
    sensitiveAttrReq.setIsSensitive(0);
    sensitiveAttrReq.setSensitiveList(new ArrayList<>());
    whExtAttrReq.setProductSkuSensitiveAttrReq(sensitiveAttrReq);
    productSkuReq.setProductSkuWhExtAttrReq(whExtAttrReq);

    ArrayList<ProductSkuReq> productSkuReqs = new ArrayList<>();
    productSkuReqs.add(productSkuReq);

    return productSkuReqs;
  }

  public List<String> uploadTemuImage(List<String> imageUrls, int imageBizType, TemuConfig temuConfig, Integer catId) {
    List<String> temuImages = new ArrayList<>();
    try {
      for (String imageUrl : imageUrls) {
        imageUrl = "https://cdn.yamibuy.net/item/" + imageUrl + ".webp";
        URL url = new URL(imageUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");

        BufferedImage image = ImageIO.read(connection.getInputStream());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);

        String format = "png";
        double press = (double) (1024 * 1024 * 2) / outputStream.size();
        if (press < 1.0) {
          //png不支持压缩，所以先转成jpeg,然后再判断是否需要压缩，
          // 如果在最开始直接将格式转为jpeg，可能导致部分图片失真
          outputStream.reset();
          connection.disconnect();
          imageUrl = imageUrl.replace(".webp", "_1200×1200.webp");
          url = new URL(imageUrl);
          connection = (HttpURLConnection) url.openConnection();
          connection.setRequestMethod("GET");

          image = ImageIO.read(connection.getInputStream());
          ImageIO.write(image, "jpeg", outputStream);
        }
        //转换为Base64字符串
        byte[] compressedImageData = outputStream.toByteArray();
        String base64Image = Base64.getEncoder().encodeToString(compressedImageData);
        String imageBase64String = String.format("data:image/%s;base64,%s", format, base64Image);

        String temuImgUrl;
        //不符合尺寸的调用temuAI裁剪接口
        if (image.getWidth() < 800 || image.getHeight() < 800) {
          temuImgUrl = temuApiService.goodsImageAiUpload(catId, imageBase64String, imageBizType, temuConfig);
        } else {
          temuImgUrl = temuApiService.goodsImageUpload(imageBase64String, imageBizType, temuConfig);
        }
        if (!StringUtils.isEmpty(temuImgUrl)) {
          temuImages.add(temuImgUrl);
        }

      }
      if (temuImages.size() == 0) {
        throw new YamibuyException("无符合要求的图片", YamibuyMessageCode.ERROR_REQUESTMESSAGE.getCode());
      }
      return temuImages;
    } catch (IOException e) {
      e.printStackTrace();
      return temuImages;
    }
  }

  private List<UploadTemuItemInfo> parseExcel(MultipartFile file) {
    ArrayList<UploadTemuItemInfo> uploadItemInfos = new ArrayList<>();
    try (Workbook wb = getWorkbook(file)) {
      Sheet sheet = wb.getSheetAt(0);
      log.info("sheet读取成功");
      if (sheet == null) {
        throw new YamibuyException("The Excel doesn't have sheet");
      }
      parseSheet(uploadItemInfos, sheet);
    } catch (IOException e) {
      log.error("batchCreateProduct resolve error", e);
      e.printStackTrace();
    }
    return uploadItemInfos;
  }

  private void parseSheet(ArrayList<UploadTemuItemInfo> uploadItemInfos, Sheet sheet) {
    // 遍历每一行
    for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
      Row row = sheet.getRow(rowNum);
      UploadTemuItemInfo uploadItemInfo = new UploadTemuItemInfo();
      // 规避只有格式没有内容的空单元格
      if (row == null || StringUtils.isEmpty(getCellStr(row, 0))) {
        continue;
      }
      uploadItemInfo.setItem_number(getCellStr(row, 0));
      uploadItemInfo.setCatName(replaceSpecialCharcters(getCellStr(row, 1)));
      uploadItemInfo.setTitle(getCellStr(row, 2));
      uploadItemInfo.setTitle_en(getCellStr(row, 3));
      uploadItemInfo.setSpecName(getCellStr(row, 4));
      uploadItemInfo.setSpecValue(getCellStr(row, 5));
      uploadItemInfo.setItem_weight(replaceSpecialCharcters((getCellStr(row, 6))));
      uploadItemInfo.setPackage_weight(replaceSpecialCharcters((getCellStr(row, 7))));
      uploadItemInfo.setAllergen_information(replaceSpecialCharcters(getCellStr(row, 8)));
      uploadItemInfo.setPrice(Double.valueOf(replaceSpecialCharcters(getCellStr(row, 9))));
      uploadItemInfo.setPackageType(replaceSpecialCharcters((getCellStr(row, 10))));
      uploadItemInfos.add(uploadItemInfo);
    }
  }

  private String getCellStr(Row row, Integer i) {
    Cell cell = row.getCell(i);
    if (cell == null) {
      return "";
    }
    CellType type = cell.getCellTypeEnum();
    switch (type) {
      case NUMERIC:
        return NumberToTextConverter.toText(cell.getNumericCellValue());
      case STRING:
        return cell.getStringCellValue();
      default:
        return "";
    }
  }

  private Workbook getWorkbook(MultipartFile file) {
    Workbook wb;
    try {
      wb = new HSSFWorkbook(file.getInputStream());
    } catch (Exception e) {
      log.debug("CloneService batchImport not 2003 , trun to 2007+ read", e);
      try {
        wb = new XSSFWorkbook(file.getInputStream());
        log.info("excel读取成功");
      } catch (Exception e1) {
        log.error("CloneService batchImport read excel Wrong", e1);
        throw new YamibuyException("Upload file is not excel");
      }
    }
    return wb;
  }

  /**
   * 去除字符窜中的空格、回车、换行符、制表符
   *
   * @param inputStr
   * @return
   */
  public String replaceSpecialCharcters(String inputStr) {
    Pattern p = Pattern.compile("\\s");
    String resultStr = "";
    if (inputStr != null) {
      Matcher m = p.matcher(inputStr);
      resultStr = m.replaceAll("");
    }
    return resultStr;
  }

  /**
   * 商品分类匹配全路径
   *
   * @param temuProduct
   * @param catPath
   * @param config
   * @return
   */
  public Integer matchCategory(TemuProduct temuProduct, String catPath, TemuConfig config) {
    List<String> catPathList = Arrays.asList(catPath.split("[/>-]"));
    if (ListUtils.isNullorEmpty(catPathList)) {
      throw new YamibuyException(ChannelMessageCode.PRODUCT_CATEGORY_MATCHING_FAILED.getCode());
    }
    String searchText = catPathList.get(catPathList.size() - 1);

    String key = String.format("temu:category:%s", searchText);
    String str = jedisClientImp.get(key);
    TemuCategoryPathDTO pathDTO;
    if (StringUtils.isNotEmpty(str)) {
      pathDTO = JSON.parseObject(str, TemuCategoryPathDTO.class);
    } else {
      List<TemuCategoryPathDTO> categoryPathDTOS = temuApiService.matchCategory(searchText, config);
      if (ListUtils.isNullorEmpty(categoryPathDTOS)) {
        throw new YamibuyException(ChannelMessageCode.PRODUCT_CATEGORY_MATCHING_FAILED.getCode());
      }
      pathDTO = categoryPathDTOS.stream().filter(path -> temu_category_level_0_list.contains(path.getCat1DTO().getCatId()))
        .map(path -> Stream.of(path.getCat1DTO(), path.getCat2DTO(), path.getCat3DTO(), path.getCat4DTO(), path.getCat5DTO(), path.getCat6DTO(), path.getCat7DTO(), path.getCat8DTO(), path.getCat9DTO(), path.getCat10DTO())
          .filter(cat -> searchText.equals(cat.getCatName()))
          .findFirst()
          .map(cat -> path)
          .orElse(null))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    }
    if (null != pathDTO) {
      jedisClientImp.set(key, JSON.toJSONString(pathDTO));
      jedisClientImp.expire(key, 3600);
      //填充分类ID
      setCategory(temuProduct, pathDTO);

      //获取最大level ID
      Optional<TemuCategory> maxCategory = Stream.of(pathDTO.getCat1DTO(), pathDTO.getCat2DTO(), pathDTO.getCat3DTO(), pathDTO.getCat4DTO(), pathDTO.getCat5DTO(), pathDTO.getCat6DTO(), pathDTO.getCat7DTO(), pathDTO.getCat8DTO(), pathDTO.getCat9DTO(), pathDTO.getCat10DTO())
        .filter(Objects::nonNull)
        .max(Comparator.comparing(TemuCategory::getCatLevel));
      if (maxCategory.isPresent()) {
        if (maxCategory.get().getCatName().equals(searchText)) {
          return maxCategory.get().getCatId();
        }
      }
    }
    jedisClientImp.del(key);
    throw new YamibuyException(ChannelMessageCode.PRODUCT_CATEGORY_MATCHING_FAILED.getCode());
  }

  private void setCategory(TemuProduct temuProduct, TemuCategoryPathDTO pathDTO) {
    temuProduct.setCat1Id(null == pathDTO.getCat1DTO() ? 0 : pathDTO.getCat1DTO().getCatId());
    temuProduct.setCat2Id(null == pathDTO.getCat2DTO() ? 0 : pathDTO.getCat2DTO().getCatId());
    temuProduct.setCat3Id(null == pathDTO.getCat3DTO() ? 0 : pathDTO.getCat3DTO().getCatId());
    temuProduct.setCat4Id(null == pathDTO.getCat4DTO() ? 0 : pathDTO.getCat4DTO().getCatId());
    temuProduct.setCat5Id(null == pathDTO.getCat5DTO() ? 0 : pathDTO.getCat5DTO().getCatId());
    temuProduct.setCat6Id(null == pathDTO.getCat6DTO() ? 0 : pathDTO.getCat6DTO().getCatId());
    temuProduct.setCat7Id(null == pathDTO.getCat7DTO() ? 0 : pathDTO.getCat7DTO().getCatId());
    temuProduct.setCat8Id(null == pathDTO.getCat8DTO() ? 0 : pathDTO.getCat8DTO().getCatId());
    temuProduct.setCat9Id(null == pathDTO.getCat9DTO() ? 0 : pathDTO.getCat9DTO().getCatId());
    temuProduct.setCat10Id(null == pathDTO.getCat10DTO() ? 0 : pathDTO.getCat10DTO().getCatId());
  }
}
