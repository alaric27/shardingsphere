/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.proxy.backend.handler.distsql.ral.updatable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.shardingsphere.distsql.parser.statement.ral.updatable.SetDistVariableStatement;
import org.apache.shardingsphere.infra.config.props.ConfigurationPropertyKey;
import org.apache.shardingsphere.infra.util.props.TypedPropertyValue;
import org.apache.shardingsphere.infra.util.props.exception.TypedPropertyValueException;
import org.apache.shardingsphere.logging.constant.LoggingConstants;
import org.apache.shardingsphere.logging.utils.LoggingUtils;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.exception.InvalidValueException;
import org.apache.shardingsphere.proxy.backend.exception.UnsupportedVariableException;
import org.apache.shardingsphere.proxy.backend.handler.distsql.ral.UpdatableRALBackendHandler;
import org.apache.shardingsphere.proxy.backend.handler.distsql.ral.common.enums.VariableEnum;
import org.apache.shardingsphere.proxy.backend.util.SystemPropertyUtil;
import org.apache.shardingsphere.transaction.api.TransactionType;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Set dist variable statement handler.
 */
public final class SetDistVariableHandler extends UpdatableRALBackendHandler<SetDistVariableStatement> {
    
    @Override
    protected void update(final ContextManager contextManager) {
        Enum<?> enumType = getEnumType(getSqlStatement().getName());
        if (enumType instanceof ConfigurationPropertyKey) {
            handleConfigurationProperty((ConfigurationPropertyKey) enumType, getSqlStatement().getValue());
        } else if (enumType instanceof VariableEnum) {
            handleVariables();
        } else {
            throw new UnsupportedVariableException(getSqlStatement().getName());
        }
    }
    
    private Enum<?> getEnumType(final String name) {
        try {
            return ConfigurationPropertyKey.valueOf(name.toUpperCase());
        } catch (final IllegalArgumentException ex) {
            return VariableEnum.getValueOf(name);
        }
    }
    
    private void handleConfigurationProperty(final ConfigurationPropertyKey propertyKey, final String value) {
        ContextManager contextManager = ProxyContext.getInstance().getContextManager();
        MetaDataContexts metaDataContexts = contextManager.getMetaDataContexts();
        Properties props = new Properties();
        props.putAll(metaDataContexts.getMetaData().getProps().getProps());
        props.put(propertyKey.getKey(), getValue(propertyKey, value));
        contextManager.getInstanceContext().getModeContextManager().alterProperties(props);
        refreshRootLogger(props);
        syncSQLShowToLoggingRule(propertyKey, metaDataContexts, value);
        syncSQLSimpleToLoggingRule(propertyKey, metaDataContexts, value);
    }
    
    private Object getValue(final ConfigurationPropertyKey propertyKey, final String value) {
        try {
            Object propertyValue = new TypedPropertyValue(propertyKey, value).getValue();
            return Enum.class.isAssignableFrom(propertyKey.getType()) ? propertyValue.toString() : propertyValue;
        } catch (final TypedPropertyValueException ex) {
            throw new InvalidValueException(value);
        }
    }
    
    private void refreshRootLogger(final Properties props) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        renewRootLoggerLevel(rootLogger, props);
    }
    
    private void renewRootLoggerLevel(final Logger rootLogger, final Properties props) {
        rootLogger.setLevel(Level.valueOf(props.getOrDefault(ConfigurationPropertyKey.SYSTEM_LOG_LEVEL.getKey(), ConfigurationPropertyKey.SYSTEM_LOG_LEVEL.getDefaultValue()).toString()));
    }
    
    private void syncSQLShowToLoggingRule(final ConfigurationPropertyKey propertyKey, final MetaDataContexts metaDataContexts, final String value) {
        if (LoggingConstants.SQL_SHOW.equalsIgnoreCase(propertyKey.getKey())) {
            LoggingUtils.getSQLLogger(metaDataContexts.getMetaData().getGlobalRuleMetaData()).ifPresent(option -> {
                option.getProps().setProperty(LoggingConstants.SQL_LOG_ENABLE, value);
                metaDataContexts.getPersistService().getGlobalRuleService().persist(metaDataContexts.getMetaData().getGlobalRuleMetaData().getConfigurations());
            });
        }
    }
    
    private void syncSQLSimpleToLoggingRule(final ConfigurationPropertyKey propertyKey, final MetaDataContexts metaDataContexts, final String value) {
        if (LoggingConstants.SQL_SIMPLE.equalsIgnoreCase(propertyKey.getKey())) {
            LoggingUtils.getSQLLogger(metaDataContexts.getMetaData().getGlobalRuleMetaData()).ifPresent(option -> {
                option.getProps().setProperty(LoggingConstants.SQL_LOG_SIMPLE, value);
                metaDataContexts.getPersistService().getGlobalRuleService().persist(metaDataContexts.getMetaData().getGlobalRuleMetaData().getConfigurations());
            });
        }
    }
    
    private void handleVariables() {
        VariableEnum variable = VariableEnum.getValueOf(getSqlStatement().getName());
        switch (variable) {
            case AGENT_PLUGINS_ENABLED:
                Boolean agentPluginsEnabled = BooleanUtils.toBooleanObject(getSqlStatement().getValue());
                SystemPropertyUtil.setSystemProperty(variable.name(), null == agentPluginsEnabled ? Boolean.FALSE.toString() : agentPluginsEnabled.toString());
                break;
            case TRANSACTION_TYPE:
                getConnectionSession().getTransactionStatus().setTransactionType(getTransactionType(getSqlStatement().getValue()));
                break;
            default:
                throw new UnsupportedVariableException(getSqlStatement().getName());
        }
    }
    
    private TransactionType getTransactionType(final String transactionTypeName) throws UnsupportedVariableException {
        try {
            return TransactionType.valueOf(transactionTypeName.toUpperCase());
        } catch (final IllegalArgumentException ex) {
            throw new UnsupportedVariableException(transactionTypeName);
        }
    }
}
