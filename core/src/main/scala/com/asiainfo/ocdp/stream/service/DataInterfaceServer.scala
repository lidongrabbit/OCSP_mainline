package com.asiainfo.ocdp.stream.service

import com.asiainfo.ocdp.stream.common.{JDBCUtil, Logging}
import com.asiainfo.ocdp.stream.config._
import com.asiainfo.ocdp.stream.constant.{DataSourceConstant, TableInfoConstant}
import com.asiainfo.ocdp.stream.event.Event
import com.asiainfo.ocdp.stream.label.Label
import com.asiainfo.ocdp.stream.tools.Json4sUtils
import org.apache.commons.lang3.StringUtils

import scala.collection.mutable._

/**
  * Created by leo on 9/16/15.
  */
class DataInterfaceServer extends Logging with Serializable {

  def getDataInterfaceInfoById(id: String): DataInterfaceConf = {

    val conf = new DataInterfaceConf()
    // modify by surq at 2015.11.09 start
    //    val sql = "select id, name, dsid, type, status, properties " +
    val sql = "select id, filter_expr,name, dsid, type, delim, status, properties " +
      // modify by surq at 2015.11.09 end
      "from " + TableInfoConstant.DataInterfaceTableName +
      " where id='" + id + "' and status = 1"

    val result = JDBCUtil.query(sql)

    if (result.length > 0) {
      val interface = result.head
      conf.setDiid(interface.get("id").get)
      conf.setName(interface.get("name").get)
      conf.setDiType(interface.get("type").get.toInt)
      // add by surq at 2015.11.09 start
      conf.set("filter_expr", interface.get("filter_expr").get, "")
      conf.set("delim", interface.get("delim").get, ",")
      // add by surq at 2015.11.09 end
      val dsconf = getDataSourceInfoById(interface.get("dsid").get)
      conf.setDsConf(dsconf)

      val propsJsonStr = interface.get("properties").getOrElse("").replace(" ", "")
      conf.setBaseSchema(Json4sUtils.jsonStr2BaseStructType(propsJsonStr, "fields"))
      conf.setBaseItemsSize((Json4sUtils.jsonStr2ArrMap(propsJsonStr, "fields")).size)
      conf.setAllItemsSchema(Json4sUtils.jsonStr2UdfStructType(propsJsonStr, "fields", "userFields"))

      val propsMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "props")
      propsMap.foreach(kvMap => {
        if (!kvMap.isEmpty) conf.set(kvMap.get("pname").get, kvMap.get("pvalue").get)
      })
    }
    conf
  }

  def getDataSourceInfoById(id: String): DataSourceConf = {
    val conf = new DataSourceConf()
    val sql = "select name,type,properties from " + TableInfoConstant.DataSourceTableName + " where id = '" + id + "'"

    val datasource = JDBCUtil.query(sql).head

    conf.setDsid(id)
    conf.setName(datasource.get("name").get)
    conf.setDsType(datasource.get("type").get)

    val propsJsonStr = datasource.get("properties").getOrElse(null)
    val propsArrMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr)
    propsArrMap.foreach { kvMap =>
      if (!kvMap.isEmpty) conf.set(kvMap.get("pname").get, kvMap.get("pvalue").get)
    }
    conf
  }

  def getLabelsByIFId(id: String): Array[Label] = {
    val sql = "select a.id, a.p_label_id, b.name, b.class_name, b.properties, a.label_id " +
      "from " + TableInfoConstant.LabelTableName +
      " a, " + TableInfoConstant.LabelDefinitionTableName + " b where a.diid = '" + id + "' and a.status = 1 and a.label_id=b.id"

    logInfo("Get all labels via sql '" + sql + "'")

    val dsdata = JDBCUtil.query(sql)

    val labelarr = ArrayBuffer[Label]()
    dsdata.foreach(x => {
      val conf = new LabelConf()
      conf.setId(x.get("id").get)
      conf.setDiid(id)
      conf.setName(x.get("name").get)
      conf.setClass_name(x.get("class_name").get)
      conf.setPlabelId(x.get("p_label_id").get)

      val propsJsonStr = x.get("properties").getOrElse(null)
      if (StringUtils.isNotEmpty(propsJsonStr)) {
        if (!Json4sUtils.isValidJsonStr(propsJsonStr)){
          throw new Exception(s"${propsJsonStr} is invalid json format.")
        }

        val labelConfMap = Json4sUtils.jsonStr2MapList(propsJsonStr)

        labelConfMap.foreach( confObject => {

          if ("props".equals(confObject._1)){
            confObject._2.foreach(props => {
              if (!props.isEmpty){
                conf.set(props.get("pname").get, props.get("pvalue").get)
              }
            })
          }
          else if ("labelItems".equals(confObject._1)){
            val fieldsList = ArrayBuffer[String]()
            confObject._2.foreach(items => {
              if (!items.isEmpty){
                fieldsList += items.get("pvalue").get
              }
            })

            //打标签字段
            conf.setFields(fieldsList.toList)
          }
          else {
            logWarning(s"${confObject._1} is invalid property.")
          }
        })
      }

      if (conf.getFields == null || conf.getFields.isEmpty){
        logWarning("Can not find any label items")
      }
      else{
        logInfo(s"The fields of label ${conf.getId} are ${conf.getFields}")
      }

      val label: Label = Class.forName(conf.getClass_name).newInstance().asInstanceOf[Label]
      label.init(conf)
      labelarr += label
    })

    //根据标签的依赖关系排序
    val labelIDMap = labelarr.map(label => (label.conf.id, label)).toMap
    val resultArray = ArrayBuffer[String]()
    labelarr.foreach(label => labelSort(label.conf.id, resultArray, labelIDMap))
    val result = ArrayBuffer[Label]()
    resultArray.foreach(id => {
      result += labelIDMap(id)
    })

    logInfo("All labels in order: " + result.toList)

    result.toArray
  }

  /**
    * 根据标签的信赖关系排序
    */
  def labelSort(labelId: String, result: ArrayBuffer[String], map: scala.collection.immutable.Map[String,Label]) {
    if (map.contains(labelId)){
      val pid = StringUtils.trim(map(labelId).conf.plabelId)
      if (StringUtils.isNotEmpty(pid) && pid != labelId) labelSort(pid, result, map)
      if (!result.contains(labelId)) result += labelId
    }else{
      logError("Invalid label id: " + labelId)
    }
  }

  def eventSort(eventId: String, result: ArrayBuffer[String], map: scala.collection.immutable.Map[String,Event]) {
    if (map.contains(eventId)){
      val p_event_id = StringUtils.trim(map(eventId).conf.p_event_id)

      if (StringUtils.isNotEmpty(p_event_id) && p_event_id != eventId) eventSort(p_event_id, result, map)

      if (!result.contains(eventId)) result += eventId
    }
    else{
      logError("Invalid event id: " + eventId)
    }
  }


  def getEventsByIFId(id: String): Array[Event] = {
    /*val sql = "select id, name, select_expr, filter_expr, p_event_id, properties " +
      "from " + TableInfoConstant.EventTableName +
      " where diid = '" + id + "' and status = 1"*/
    val sql = "select id, name, select_expr, filter_expr, p_event_id, properties " +
      "from " + TableInfoConstant.EventTableName +
      " where diid = '" + id + "' and status = 1"
    val data = JDBCUtil.query(sql)

    val eventarr = ArrayBuffer[Event]()
    data.foreach(x => {
      val conf = new EventConf()
      conf.setId(x.get("id").get)
      conf.setInIFId(id)
      conf.setName(x.get("name").get)
      conf.setSelect_expr(x.get("select_expr").get.replace(" ", ""))
      conf.setFilte_expr(x.get("filter_expr").get)
      conf.setP_event_id(x.get("p_event_id").get)

      val propsJsonStr = x.get("properties").getOrElse(null)


      val propsMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "props")
      propsMap.foreach(kvMap => conf.set(kvMap.get("pname").get, kvMap.get("pvalue").get))

      // 业务对应的输出数据接口配置，每个业务一个输出事件接口
      // surq: 存放输出信息：[interfaceID->,delim->,interval->]
      val outputIFIdsArrMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "output_dis")
      val outputIFIdArr = ArrayBuffer[DataInterfaceConf]()

      //TODO send_interval should bound on dataInterface, now just one di , so set the last value
      var send_interval = 0
      var delim = ","
      outputIFIdsArrMap.foreach(kvMap => {
        val diid = kvMap.get("diid").get
        // 加载Interface内容
        outputIFIdArr += (getDataInterfaceInfoById(diid))
        send_interval = kvMap.get("interval").get.toInt
        delim = kvMap.get("delim").get
      })
      conf.setOutIFIds(outputIFIdArr.toArray)
      conf.setInterval(send_interval)
      conf.setDelim(delim)
      val event = new Event
      event.init(conf)
      eventarr += event
    })

    //根据event的依赖关系排序
    val eventIDMap = eventarr.map(event => (event.conf.id, event)).toMap

    val resultArray = ArrayBuffer[String]()

    eventarr.foreach(event => eventSort(event.conf.id, resultArray, eventIDMap))

    val result = ArrayBuffer[Event]()

    resultArray.foreach(id => {
      result += eventIDMap(id)
    })

    logInfo("All events in order: " + result.toList)

    result.toArray
  }

  def getSubjectInfoById(id: String): SubjectConf = {
    val conf = new SubjectConf()

    val sql = "select name, properties " +
      "from " + TableInfoConstant.BusinessEventTableName +
      " where id='" + id + "' and status = 1"

    val subject = JDBCUtil.query(sql).head
    conf.setId(id)
    conf.setName(subject.get("name").get)

    val propsJsonStr = subject.get("properties").getOrElse(null)
    var propsMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "events")

    val events = Map[String, String]()
    propsMap.foreach(kvMap => {
      if (!kvMap.isEmpty) {
        events += (kvMap.get("eventId").get -> kvMap.get("select_expr").get)
      }
    })
    conf.setEvents(events.toMap)

    propsMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "props")
    propsMap.foreach(kvMap => {
      if (!kvMap.isEmpty) conf.set(kvMap.get("pname").get, kvMap.get("pvalue").get)
    })

    conf
  }

  def getDataInterfaceByEventId(ids: Array[String]): Array[DataInterfaceConf] = {

    val sql = "select properties " +
      "from " + TableInfoConstant.EventTableName +
      " where id in (" + ids.map("'" + _ + "'").mkString(",") + ") and status = 1"
    val data = JDBCUtil.query(sql)

    val dfarr = new ArrayBuffer[DataInterfaceConf]()
    data.map(x => {
      val propsJsonStr = x.get("properties").getOrElse(null)
      val outputIFIdsArrMap = Json4sUtils.jsonStr2ArrMap(propsJsonStr, "output_dis")
      outputIFIdsArrMap.foreach(kvMap => {
        val ifid = kvMap.get("pvalue").get
        val conf = getDataInterfaceInfoById(ifid)
        if (DataSourceConstant.KAFKA_TYPE.equals(conf.getDiType))
          dfarr.append(conf)
      })
    })

    dfarr.toArray
  }
}
