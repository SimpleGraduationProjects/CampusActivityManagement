/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 abel533@gmail.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.my.xymh.pagehelper;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.annotation.ProviderSqlSource;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mybatis - sql????????????????????????count???MappedStatement?????????????????????
 *
 * @author liuzh/abel533/isea533
 * @since 3.3.0
 * ???????????? : http://git.oschina.net/free/Mybatis_PageHelper
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class SqlUtil {
    private static final List<ResultMapping> EMPTY_RESULTMAPPING = new ArrayList<ResultMapping>(0);
    //?????????id??????
    private static final String SUFFIX_PAGE = "_PageHelper";
    //count?????????id??????
    private static final String SUFFIX_COUNT = SUFFIX_PAGE + "_Count";
    //?????????????????????
    private static final String PAGEPARAMETER_FIRST = "First" + SUFFIX_PAGE;
    //?????????????????????
    private static final String PAGEPARAMETER_SECOND = "Second" + SUFFIX_PAGE;

    private static final String PROVIDER_OBJECT = "_provider_object";

    private static final ObjectFactory DEFAULT_OBJECT_FACTORY = new DefaultObjectFactory();
    private static final ObjectWrapperFactory DEFAULT_OBJECT_WRAPPER_FACTORY = new DefaultObjectWrapperFactory();

    /**
     * ?????????????????????????????????Mybatis?????????
     *
     * @param object ????????????
     * @return
     */
    private static MetaObject forObject(Object object) {
        return MetaObject.forObject(object, DEFAULT_OBJECT_FACTORY, DEFAULT_OBJECT_WRAPPER_FACTORY);
    }

    private Parser sqlParser;

    //??????????????? - ?????????????????????????????????
    public enum Dialect {
        mysql, mariadb, sqlite, oracle, hsqldb, postgresql
    }

    /**
     * ????????????
     *
     * @param strDialect
     */
    public SqlUtil(String strDialect) {
        if (strDialect == null || "".equals(strDialect)) {
            throw new IllegalArgumentException("Mybatis????????????????????????dialect??????!");
        }
        try {
            Dialect dialect = Dialect.valueOf(strDialect);
            String sqlParserClass = this.getClass().getPackage().getName() + ".SqlParser";
            try {
                //??????SqlParser????????????jsqlparser-x.x.x.jar
                Class.forName("net.sf.jsqlparser.statement.select.Select");
                sqlParser = (Parser) Class.forName(sqlParserClass).getConstructor(Dialect.class).newInstance(dialect);
            } catch (Exception e) {
                //???????????????????????????
            }
            if (sqlParser == null) {
                sqlParser = SimpleParser.newParser(dialect);
            }
        } catch (IllegalArgumentException e) {
            String dialects = null;
            for (Dialect d : Dialect.values()) {
                if (dialects == null) {
                    dialects = d.toString();
                } else {
                    dialects += "," + d;
                }
            }
            throw new IllegalArgumentException("Mybatis????????????dialect??????????????????????????????[" + dialects + "]");
        }
    }

    /**
     * ??????????????????
     *
     * @param parameterObject
     * @param page
     * @return
     */
    public Map setPageParameter(MappedStatement ms, Object parameterObject, Page page) {
        BoundSql boundSql = ms.getBoundSql(parameterObject);
        return sqlParser.setPageParameter(ms, parameterObject, boundSql, page);
    }

    /**
     * ??????count?????????MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param args
     */
    public void processCountMappedStatement(MappedStatement ms, SqlSource sqlSource, Object[] args) {
        args[0] = getMappedStatement(ms, sqlSource, args[1], SUFFIX_COUNT);
    }

    /**
     * ?????????????????????MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param page
     * @param args
     */
    public void processPageMappedStatement(MappedStatement ms, SqlSource sqlSource, Page page, Object[] args) {
        args[0] = getMappedStatement(ms, sqlSource, args[1], SUFFIX_PAGE);
        //????????????
        args[1] = setPageParameter((MappedStatement) args[0], args[1], page);
    }

    /**
     * ??????SQL
     */
    public static interface Parser {
        void isSupportedSql(String sql);

        String getCountSql(String sql);

        String getPageSql(String sql);

        Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page);
    }

    public static abstract class SimpleParser implements Parser {
        public static Parser newParser(Dialect dialect) {
            Parser parser = null;
            switch (dialect) {
                case mysql:
                case mariadb:
                case sqlite:
                    parser = new MysqlParser();
                    break;
                case oracle:
                    parser = new OracleParser();
                    break;
                case hsqldb:
                    parser = new HsqldbParser();
                    break;
                case postgresql:
                default:
                    parser = new PostgreSQLParser();
            }
            return parser;
        }

        public void isSupportedSql(String sql) {
            if (sql.trim().toUpperCase().endsWith("FOR UPDATE")) {
                throw new RuntimeException("???????????????????????????for update???sql");
            }
        }

        /**
         * ????????????sql - ??????????????????????????????????????????????????????
         *
         * @param sql ?????????sql
         * @return ??????count??????sql
         */
        public String getCountSql(final String sql) {
            isSupportedSql(sql);
            StringBuilder stringBuilder = new StringBuilder(sql.length() + 40);
            stringBuilder.append("select count(*) from (");
            stringBuilder.append(sql);
            stringBuilder.append(") tmp_count");
            return stringBuilder.toString();
        }

        /**
         * ????????????sql - ??????????????????????????????????????????????????????
         *
         * @param sql ?????????sql
         * @return ????????????sql
         */
        public abstract String getPageSql(String sql);

        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page) {
            Map paramMap = null;
            if (parameterObject == null) {
                paramMap = new HashMap();
            } else if (parameterObject instanceof Map) {
                paramMap = (Map) parameterObject;
            } else {
                paramMap = new HashMap();
                //??????sql?????????????????????????????????ParameterMapping?????????????????????????????????????????????????????????getter??????
                //TypeHandlerRegistry?????????????????????????????????????????????????????????????????????
                boolean hasTypeHandler = ms.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
                MetaObject metaObject = forObject(parameterObject);
                //???????????????????????????MyProviderSqlSource????????????
                if (ms.getSqlSource() instanceof MyProviderSqlSource) {
                    paramMap.put(PROVIDER_OBJECT, parameterObject);
                }
                if (!hasTypeHandler) {
                    for (String name : metaObject.getGetterNames()) {
                        paramMap.put(name, metaObject.getValue(name));
                    }
                }
                //????????????????????????????????????????????????????????????????????????
                if (boundSql.getParameterMappings() != null && boundSql.getParameterMappings().size() > 0) {
                    for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                        String name = parameterMapping.getProperty();
                        if (!name.equals(PAGEPARAMETER_FIRST)
                                && !name.equals(PAGEPARAMETER_SECOND)
                                && paramMap.get(name) == null) {
                            if (hasTypeHandler
                                    || parameterMapping.getJavaType().equals(parameterObject.getClass())) {
                                paramMap.put(name, parameterObject);
                            } else {
                                try {
                                	paramMap.put(name, metaObject.getValue(name));
                                } catch (Exception e) {
									// ?????????????????????????????????
								}
                            }
                        }
                    }
                }
            }
            return paramMap;
        }
    }

    //Mysql
    private static class MysqlParser extends SimpleParser {
        @Override
        public String getPageSql(String sql) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
            sqlBuilder.append(sql);
            sqlBuilder.append(" limit ?,?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, page);
            paramMap.put(PAGEPARAMETER_FIRST, page.getStartRow());
            paramMap.put(PAGEPARAMETER_SECOND, page.getPageSize());
            return paramMap;
        }
    }

    //Oracle
    private static class OracleParser extends SimpleParser {
        @Override
        public String getPageSql(String sql) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 120);
            sqlBuilder.append("select * from ( select tmp_page.*, rownum row_id from ( ");
            sqlBuilder.append(sql);
            sqlBuilder.append(" ) tmp_page where rownum <= ? ) where row_id > ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, page);
            paramMap.put(PAGEPARAMETER_FIRST, page.getEndRow());
            paramMap.put(PAGEPARAMETER_SECOND, page.getStartRow());
            return paramMap;
        }
    }

    //Oracle
    private static class HsqldbParser extends SimpleParser {
        @Override
        public String getPageSql(String sql) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 20);
            sqlBuilder.append(sql);
            sqlBuilder.append(" limit ? offset ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, page);
            paramMap.put(PAGEPARAMETER_FIRST, page.getPageSize());
            paramMap.put(PAGEPARAMETER_SECOND, page.getStartRow());
            return paramMap;
        }
    }

    //PostgreSQL
    private static class PostgreSQLParser extends SimpleParser {
        @Override
        public String getPageSql(String sql) {
            StringBuilder sqlBuilder = new StringBuilder(sql.length() + 14);
            sqlBuilder.append(sql);
            sqlBuilder.append(" limit ? offset ?");
            return sqlBuilder.toString();
        }

        @Override
        public Map setPageParameter(MappedStatement ms, Object parameterObject, BoundSql boundSql, Page page) {
            Map paramMap = super.setPageParameter(ms, parameterObject, boundSql, page);
            paramMap.put(PAGEPARAMETER_FIRST, page.getPageSize());
            paramMap.put(PAGEPARAMETER_SECOND, page.getStartRow());
            return paramMap;
        }
    }

    /**
     * ???????????????SqlSource
     */
    private class MyDynamicSqlSource implements SqlSource {
        private Configuration configuration;
        private SqlNode rootSqlNode;
        /**
         * ?????????????????????count?????????????????????
         */
        private Boolean count;

        public MyDynamicSqlSource(Configuration configuration, SqlNode rootSqlNode, Boolean count) {
            this.configuration = configuration;
            this.rootSqlNode = rootSqlNode;
            this.count = count;
        }

        public BoundSql getBoundSql(Object parameterObject) {
            DynamicContext context = new DynamicContext(configuration, parameterObject);
            rootSqlNode.apply(context);
            SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
            Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
            SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
            if (count) {
                sqlSource = getCountSqlSource(configuration, sqlSource, parameterObject);
            } else {
                sqlSource = getPageSqlSource(configuration, sqlSource, parameterObject);
            }
            BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
            //??????????????????
            for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
                boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
            }
            return boundSql;
        }
    }

    /**
     * ?????????ProviderSqlSource???????????????
     */
    private class MyProviderSqlSource implements SqlSource {
        private Configuration configuration;
        private ProviderSqlSource providerSqlSource;

        /**
         * ?????????????????????count?????????????????????
         */
        private Boolean count;

        private MyProviderSqlSource(Configuration configuration, ProviderSqlSource providerSqlSource, Boolean count) {
            this.configuration = configuration;
            this.providerSqlSource = providerSqlSource;
            this.count = count;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            BoundSql boundSql = null;
            if (parameterObject instanceof Map && ((Map) parameterObject).containsKey(PROVIDER_OBJECT)) {
                boundSql = providerSqlSource.getBoundSql(((Map) parameterObject).get(PROVIDER_OBJECT));
            } else {
                boundSql = providerSqlSource.getBoundSql(parameterObject);
            }
            if (count) {
                return new BoundSql(
                        configuration,
                        sqlParser.getCountSql(boundSql.getSql()),
                        boundSql.getParameterMappings(),
                        parameterObject);
            } else {
                return new BoundSql(
                        configuration,
                        sqlParser.getPageSql(boundSql.getSql()),
                        getPageParameterMapping(configuration, boundSql),
                        parameterObject);
            }
        }
    }


    /**
     * ??????ms - ?????????????????????ms????????????????????????????????????????????????????????????
     *
     * @param ms
     * @param sqlSource
     * @param suffix
     * @return
     */
    private MappedStatement getMappedStatement(MappedStatement ms, SqlSource sqlSource, Object parameterObject, String suffix) {
        MappedStatement qs = null;
        try {
            qs = ms.getConfiguration().getMappedStatement(ms.getId() + suffix);
        } catch (Exception e) {
            //ignore
        }
        if (qs == null) {
            //??????????????????MappedStatement
            qs = newMappedStatement(ms, getsqlSource(ms, sqlSource, parameterObject, suffix), suffix);
            try {
                ms.getConfiguration().addMappedStatement(qs);
            } catch (Exception e) {
                //ignore
            }
        }
        return qs;
    }

    /**
     * ??????count????????????????????????MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param suffix
     * @return
     */
    private MappedStatement newMappedStatement(MappedStatement ms, SqlSource sqlSource, String suffix) {
        String id = ms.getId() + suffix;
        MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(), id, sqlSource, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.fetchSize(ms.getFetchSize());
        builder.statementType(ms.getStatementType());
        builder.keyGenerator(ms.getKeyGenerator());
        if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
            StringBuilder keyProperties = new StringBuilder();
            for (String keyProperty : ms.getKeyProperties()) {
                keyProperties.append(keyProperty).append(",");
            }
            keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
            builder.keyProperty(keyProperties.toString());
        }
        builder.timeout(ms.getTimeout());
        builder.parameterMap(ms.getParameterMap());
        if (suffix == SUFFIX_PAGE) {
            builder.resultMaps(ms.getResultMaps());
        } else {
            //count???????????????int
            List<ResultMap> resultMaps = new ArrayList<ResultMap>();
            ResultMap resultMap = new ResultMap.Builder(ms.getConfiguration(), id, int.class, EMPTY_RESULTMAPPING).build();
            resultMaps.add(resultMap);
            builder.resultMaps(resultMaps);
        }
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());

        return builder.build();
    }

    /**
     * ????????????????????????????????????sql
     *
     * @param ms
     * @return
     */
    public boolean isDynamic(MappedStatement ms) {
        return ms.getSqlSource() instanceof DynamicSqlSource;
    }

    /**
     * ????????????sqlSource
     *
     * @param ms
     * @param sqlSource
     * @param parameterObject
     * @param suffix
     * @return
     */
    private SqlSource getsqlSource(MappedStatement ms, SqlSource sqlSource, Object parameterObject, String suffix) {
        //1. ???XMLLanguageDriver.java???XMLScriptBuilder.java????????????????????????SqlSource
        //2. ?????????????????????ProviderSqlSource
        //3. ??????RawSqlSource???????????????????????????
        //???????????????sql
        if (isDynamic(ms)) {
            MetaObject msObject = forObject(ms);
            SqlNode sqlNode = (SqlNode) msObject.getValue("sqlSource.rootSqlNode");
            MixedSqlNode mixedSqlNode = null;
            if (sqlNode instanceof MixedSqlNode) {
                mixedSqlNode = (MixedSqlNode) sqlNode;
            } else {
                List<SqlNode> contents = new ArrayList<SqlNode>(1);
                contents.add(sqlNode);
                mixedSqlNode = new MixedSqlNode(contents);
            }
            return new MyDynamicSqlSource(ms.getConfiguration(), mixedSqlNode, suffix == SUFFIX_COUNT);
        } else if (sqlSource instanceof ProviderSqlSource) {
            return new MyProviderSqlSource(ms.getConfiguration(), (ProviderSqlSource) sqlSource, suffix == SUFFIX_COUNT);
        }
        //?????????????????????sql
        else if (suffix == SUFFIX_PAGE) {
            //????????????sql
            return getPageSqlSource(ms.getConfiguration(), sqlSource, parameterObject);
        }
        //???????????????count-sql
        else {
            return getCountSqlSource(ms.getConfiguration(), sqlSource, parameterObject);
        }
    }

    /**
     * ????????????????????????
     *
     * @param configuration
     * @param boundSql
     * @return
     */
    private List<ParameterMapping> getPageParameterMapping(Configuration configuration, BoundSql boundSql) {
        List<ParameterMapping> newParameterMappings = new ArrayList<ParameterMapping>();
        newParameterMappings.addAll(boundSql.getParameterMappings());
        newParameterMappings.add(new ParameterMapping.Builder(configuration, PAGEPARAMETER_FIRST, Integer.class).build());
        newParameterMappings.add(new ParameterMapping.Builder(configuration, PAGEPARAMETER_SECOND, Integer.class).build());
        return newParameterMappings;
    }

    /**
     * ???????????????sqlSource
     *
     * @param configuration
     * @param sqlSource
     * @return
     */
    private SqlSource getPageSqlSource(Configuration configuration, SqlSource sqlSource, Object parameterObject) {
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        return new StaticSqlSource(configuration, sqlParser.getPageSql(boundSql.getSql()), getPageParameterMapping(configuration, boundSql));
    }

    /**
     * ??????count???sqlSource
     *
     * @param sqlSource
     * @return
     */
    private SqlSource getCountSqlSource(Configuration configuration, SqlSource sqlSource, Object parameterObject) {
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        return new StaticSqlSource(configuration, sqlParser.getCountSql(boundSql.getSql()), boundSql.getParameterMappings());
    }

    /**
     * ??????[???????????????]count?????????sql
     *
     * @param dialet      ???????????????
     * @param originalSql ???sql
     */
    public static void testSql(String dialet, String originalSql) {
        SqlUtil sqlUtil = new SqlUtil(dialet);
        String countSql = sqlUtil.sqlParser.getCountSql(originalSql);
        System.out.println(countSql);
        String pageSql = sqlUtil.sqlParser.getPageSql(originalSql);
        System.out.println(pageSql);
    }
}