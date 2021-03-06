package com.alibaba.datax.plugin.writer.mqwriter;

import ch.qos.logback.classic.Logger;
import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.element.StringColumn;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.spi.ErrorCode;
import com.alibaba.datax.common.spi.Writer;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.writer.rediswriter.MqWriterErrorCode;
import com.alibaba.datax.plugin.writer.rediswriter.SupportMqDataType;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.hadoop.io.Text;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.slf4j.LoggerFactory;

import java.util.*;


public class MqWriter extends Writer {


    private static Logger logger = (Logger) LoggerFactory.getLogger("MqWriter");

    private static String servers = null;
    private static String password = null;
    private static String username = null;
    private static String group = null;
    private static String topic = null;
    private static String tag = null;




    public static class Job extends Writer.Job {
        private Configuration originalConfig = null;


        @Override
        public void init() {
            this.originalConfig = super.getPluginJobConf();
            logger.info("RedisWriter");

            //connection init
            servers = this.originalConfig.getNecessaryValue(Constant.BOOTSTRAP_SERVERS,
                    DBUtilErrorCode.CONF_ERROR);
            if (Strings.isNullOrEmpty(servers)) {
                logger.error("mqreader server host and port must not be null");
                throw DataXException.asDataXException(MqWriterErrorCode.CONFIG_INVALID_EXCEPTION,
                        new RuntimeException());
            }

            username = this.originalConfig.getString(Constant.USERNAME,
                    null);
            password = this.originalConfig.getString(Constant.PASSWORD,
                    null);
            group = this.originalConfig.getString(Constant.GROUP,
                    null);
            tag = this.originalConfig.getString(Constant.TAG,
                    null);
            logger.info("RedisWriter init success");
        }

        @Override
        public void prepare() {
            logger.info("prepare() begin...");
            super.prepare();
        }

        @Override
        public List<Configuration> split(int mandatoryNumber) {
            logger.info("split() begin...");
            List<Configuration> list = new ArrayList<Configuration>();
            list.add(this.originalConfig);
            logger.info("split() end...");
            return list;
        }

        @Override
        public void post() {

        }

        @Override
        public void destroy() {

        }


    }

    public static class Task extends Writer.Task {
        private Configuration writerSliceConfig;
        private static long errorCount = 0;
        private List<Map<String,String>> topics = new ArrayList<Map<String,String>>();

        @Override
        public void init() {
            this.writerSliceConfig = super.getPluginJobConf();
            logger.info("MqWriter task init");
            List<Configuration> topicsTemp;
            topicsTemp = this.writerSliceConfig.getListConfiguration(Constant.TOPICS);
            this.topics.clear();
            for (Configuration obj : topicsTemp) {
                logger.info("mq writer add topic " + obj.toString() + " tag ");
                String tag = obj.getString("tag", null);
                String topic = obj.getString("topic", null);
                if (topic == null) {
                    throw DataXException.asDataXException(new ErrorCode() {
                                                              @Override
                                                              public String getCode() {
                                                                  return "mq-01";
                                                              }

                                                              @Override
                                                              public String getDescription() {
                                                                  return "未填写topic";
                                                              }
                                                          },
                            String.format("您提供配置topic有误，[%s]是必填参数.", "topic"));
                }
            }
        }

        @Override
        public void prepare() {

        }

        public void startWrite(RecordReceiver recordReceiver) {
            logger.info("MqWriter start write ");
            //需要一个producer group名字作为构造方法的参数，这里为producer1
            DefaultMQProducer producer = new DefaultMQProducer(group);
            //设置NameServer地址,此处应改为实际NameServer地址，多个地址之间用；分隔
            //NameServer的地址必须有，但是也可以通过环境变量的方式设置，不一定非得写死在代码里
            producer.setNamesrvAddr(servers);
            producer.setVipChannelEnabled(false);
            //为避免程序启动的时候报错，添加此代码，可以让rocketMq自动创建topickey
            producer.setCreateTopicKey("AUTO_CREATE_TOPIC_KEY");
            try {
                producer.start();
            } catch (MQClientException e) {
                e.printStackTrace();
                logger.error(e.getErrorMessage());
                return;
            }
            Record record = null;
            while ((record = recordReceiver.getFromReader()) != null) {
                String line = readOneTransportRecord(record, this.writerSliceConfig, this.getTaskPluginCollector());


                try {
                    List<Configuration> topicsTemp;
                    topicsTemp = this.writerSliceConfig.getListConfiguration(Constant.TOPICS);
                    this.topics.clear();
                    for (Configuration obj : topicsTemp) {
                        String tag = obj.getString("tag", null);
                        String topic = obj.getString("topic", null);
                        Message message = new Message(topic, tag,
                                (line).getBytes(RemotingHelper.DEFAULT_CHARSET));
                        SendResult sendResult = producer.send(message);
                        if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                            logger.info("your message which id" + sendResult.getMsgId() + " may something wrong with it " + sendResult.getSendStatus());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.error(e.getMessage());
                    break;
                }
            }

            producer.shutdown();
        }


        @Override
        public void post() {
        }

        @Override
        public void destroy() {
            logger.info("RedisWriter start destroy ");

        }


        public String readOneTransportRecord(Record recordTemp, Configuration config,
                                             TaskPluginCollector taskPluginCollector) {
            char fieldDelimiter = config.getChar(Constant.FIELD_DELIMITER);
            String result = null;
            List<Configuration> columns = config.getListConfiguration(Constant.COLUMN);
            try {
                Record record = recordTemp;
//                if ((record = lineReceiver.getFromReader()) != null) {
                MutablePair<Text, Boolean> transportResult = transportOneRecord(record, fieldDelimiter, columns, taskPluginCollector);
                if (!transportResult.getRight()) {
                    result = transportResult.left.toString();
//                        System.out.println("【格式化结果】" + result);
//                    }
                }
            } catch (Exception e) {
                String message = String.format("发生解析异常[%s],请检查！", e.getMessage());
                logger.error(message);
                throw DataXException.asDataXException(MqWriterErrorCode.Write_FILE_IO_ERROR, e);
            }
            return result;
        }


        public static MutablePair<Text, Boolean> transportOneRecord(Record record,
                                                                    char fieldDelimiter,
                                                                    List<Configuration> columnsConfiguration,
                                                                    TaskPluginCollector taskPluginCollector) {
//          LOG.info("columnsConfiguration:" + columnsConfiguration.size());
            MutablePair<List<Object>, Boolean> transportResultListTemp = transportOneRecord(record, columnsConfiguration, taskPluginCollector);
            //保存<转换后的数据,是否是脏数据>
            MutablePair<Text, Boolean> transportResult = new MutablePair<Text, Boolean>();
            transportResult.setRight(false);
            if (null != transportResultListTemp) {
                Text recordResult = new Text(StringUtils.join(transportResultListTemp.getLeft(), fieldDelimiter));
                transportResult.setRight(transportResultListTemp.getRight());
                transportResult.setLeft(recordResult);
            }
            return transportResult;
        }


        public static MutablePair<List<Object>, Boolean> transportOneRecord(
                Record record, List<Configuration> columnsConfiguration,
                TaskPluginCollector taskPluginCollector) {

            MutablePair<List<Object>, Boolean> transportResult = new MutablePair<List<Object>, Boolean>();
            transportResult.setRight(false);
            List<Object> recordList = Lists.newArrayList();
            int recordLength = 0;

            //首先数据列数由hdfswriter这边配置的列数目确定，如果没有配置，读取reader的列数
            if (columnsConfiguration != null && columnsConfiguration.size() > 0) {
                recordLength = columnsConfiguration.size();
            } else {
                recordLength = record.getColumnNumber();
            }

            if (0 != recordLength) {
                Column column;
                for (int i = 0; i < recordLength; i++) {

                    //record越界,少于的情况
                    try {
                        Column columnTest = record.getColumn(i);
                    } catch (IndexOutOfBoundsException e) {
                        //由hdfswriter决定行数
                        if (recordLength == columnsConfiguration.size()) {
                            recordList.add((new StringColumn(null)).asString());
                            //由reader决定行数
                        } else {
                            throw DataXException
                                    .asDataXException(
                                            MqWriterErrorCode.ERROR_DATA_ERROR,
                                            String.format(
                                                    "原文件脏数据. 数组越界 请检查源文件,或则重新配置."));
                        }
                        continue;
                    }
                    column = record.getColumn(i);
                    //todo as method
                    if (column != null && null != column.getRawData()) {
                        String rowData = column.getRawData().toString();

                        //columnsConfiguration.size()决定行数越界时
                        try {
                            Configuration conf = columnsConfiguration.get(i);
                        } catch (IndexOutOfBoundsException e) {
                            errorCount++;
                            if (errorCount > 1 && errorCount % 500000 == 1) {
                                String message = String.format("源文件有脏数据发生越界异常,数目达到:%s,此处写入前:%d字段,跳过该异常！",
                                        errorCount, i);
                                System.out.println(message);
                            }
                            continue;
                        }

                        SupportMqDataType columnType = SupportMqDataType.valueOf(
                                columnsConfiguration.get(i).getString(Constant.TYPE).toUpperCase());
                        //根据writer端类型配置做类型转换
                        try {
                            switch (columnType) {
                                case TINYINT:
                                    recordList.add(Byte.valueOf(rowData));
                                    break;
                                case SMALLINT:
                                    recordList.add(Short.valueOf(rowData));
                                    break;
                                case INT:
                                    recordList.add(Integer.valueOf(rowData));
                                    break;
                                case BIGINT:
                                case LONG:
                                    recordList.add(column.asLong());
                                    break;
                                case FLOAT:
                                    recordList.add(Float.valueOf(rowData));
                                    break;
                                case DOUBLE:
                                    recordList.add(column.asDouble());
                                    break;
                                case STRING:
                                case VARCHAR:
                                case CHAR:
                                    recordList.add(column.asString());
                                    break;
                                case BOOLEAN:
                                    recordList.add(column.asBoolean());
                                    break;
                                case DATE:
                                    recordList.add(new java.sql.Date(column.asDate().getTime()));
                                    break;
                                case TIMESTAMP:
                                    recordList.add(new java.sql.Timestamp(column.asDate().getTime()));
                                    break;
                                default:
                                    throw DataXException
                                            .asDataXException(
                                                    MqWriterErrorCode.ILLEGAL_VALUE,
                                                    String.format(
                                                            "您的配置文件中的列配置信息有误. 因为DataX 不支持数据库写入这种字段类型. 字段名:[%s], 字段类型:[%d]. 请修改表中该字段的类型或者不同步该字段.",
                                                            columnsConfiguration.get(i).getString(Constant.NAME),
                                                            columnsConfiguration.get(i).getString(Constant.TYPE)));
                            }
                        } catch (Exception e) {
                            // warn: 此处认为脏数据
                            String message = String.format(
                                    "字段类型转换错误：你目标字段为[%s]类型，实际字段值为[%s].",
                                    columnsConfiguration.get(i).getString(Constant.TYPE), column.getRawData().toString());
                            taskPluginCollector.collectDirtyRecord(record, message);
                            transportResult.setRight(true);
                            break;
                        }
                    } else {
                        // warn: it's all ok if nullFormat is null
                        recordList.add(null);
                    }
                }
            }
            transportResult.setLeft(recordList);
            return transportResult;
        }

    }
}
