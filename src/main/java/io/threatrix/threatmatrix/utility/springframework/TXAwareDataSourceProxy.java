package io.threatrix.threatmatrix.utility.springframework;

import org.springframework.amqp.rabbit.connection.ConnectionProxy;
import org.springframework.lang.Nullable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TXAwareDataSourceProxy  {

    private boolean reobtainTransactionalConnections = false;

    private DataSource targetDataSource=null;


    /**
     * Create a new TransactionAwareDataSourceProxy.
     */
    public TXAwareDataSourceProxy() {
    }

    /**
     * Create a new TransactionAwareDataSourceProxy.
     * @param targetDataSource the target DataSource
     */
    public TXAwareDataSourceProxy(DataSource targetDataSource) {
        this.targetDataSource=targetDataSource;
    }

    /**
     * Specify whether to reobtain the target Connection for each operation
     * performed within a transaction.
     * <p>The default is "false". Specify "true" to reobtain transactional
     * Connections for every call on the Connection proxy; this is advisable
     * on JBoss if you hold on to a Connection handle across transaction boundaries.
     * <p>The effect of this setting is similar to the
     * "hibernate.connection.release_mode" value "after_statement".
     */
    public void setReobtainTransactionalConnections(boolean reobtainTransactionalConnections) {
        this.reobtainTransactionalConnections = reobtainTransactionalConnections;
    }


    /**
     * Wraps the given Connection with a proxy that delegates every method call to it
     * but delegates {@code close()} calls to DataSourceUtils.
     * @param targetDataSource the DataSource that the Connection came from
     * @return the wrapped Connection
     * @see java.sql.Connection#close()

     */
    protected Connection getTransactionAwareConnectionProxy(DataSource targetDataSource) {
        return (Connection) Proxy.newProxyInstance(
            ConnectionProxy.class.getClassLoader(),
            new Class<?>[] {ConnectionProxy.class},
            null);//new TransactionAwareInvocationHandler(targetDataSource)
    }

    /**
     * Determine whether to obtain a fixed target Connection for the proxy
     * or to reobtain the target Connection for each operation.
     * <p>The default implementation returns {@code true} for all
     * standard cases. This can be overridden through the
     * {@link #setReobtainTransactionalConnections "reobtainTransactionalConnections"}
     * flag, which enforces a non-fixed target Connection within an active transaction.
     * Note that non-transactional access will always use a fixed Connection.
     * @param targetDataSource the target DataSource
     */
    protected boolean shouldObtainFixedConnection(DataSource targetDataSource) {
        return false;
        //return (!TransactionSynchronizationManager.isSynchronizationActive() ||
        //    !this.reobtainTransactionalConnections);
    }


    /**
     * Invocation handler that delegates close calls on JDBC Connections
     * to DataSourceUtils for being aware of thread-bound transactions.
     */
    private class TransactionAwareInvocationHandler implements InvocationHandler {

        private final DataSource targetDataSource;

        @Nullable
        private Connection target;

        private boolean closed = false;

        public TransactionAwareInvocationHandler(DataSource targetDataSource) {
            this.targetDataSource = targetDataSource;
        }

        @Override
        @Nullable
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Invocation on ConnectionProxy interface coming in...

            switch (method.getName()) {
                case "equals":
                    // Only considered as equal when proxies are identical.
                    return (proxy == args[0]);
                case "hashCode":
                    // Use hashCode of Connection proxy.
                    return System.identityHashCode(proxy);
                case "toString":
                    // Allow for differentiating between the proxy and the raw Connection.
                    StringBuilder sb = new StringBuilder("Transaction-aware proxy for target Connection ");
                    if (this.target != null) {
                        sb.append("[").append(this.target.toString()).append("]");
                    }
                    else {
                        sb.append(" from DataSource [").append(this.targetDataSource).append("]");
                    }
                    return sb.toString();
                case "close":
                    // Handle close method: only close if not within a transaction.
                    DataSourceUtils.doReleaseConnection(this.target, this.targetDataSource);
                    this.closed = true;
                    return null;
                case "isClosed":
                    return this.closed;
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    break;
                case "isWrapperFor":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                    break;
            }

            if (this.target == null) {
                if (method.getName().equals("getWarnings") || method.getName().equals("clearWarnings")) {
                    // Avoid creation of target Connection on pre-close cleanup (e.g. Hibernate Session)
                    return null;
                }
                if (this.closed) {
                    throw new SQLException("Connection handle already closed");
                }
                if (shouldObtainFixedConnection(this.targetDataSource)) {
                    this.target = DataSourceUtils.doGetConnection(this.targetDataSource);
                }
            }
            Connection actualTarget = this.target;
            if (actualTarget == null) {
                actualTarget = DataSourceUtils.doGetConnection(this.targetDataSource);
            }

            if (method.getName().equals("getTargetConnection")) {
                // Handle getTargetConnection method: return underlying Connection.
                return actualTarget;
            }

            // Invoke method on target Connection.
            try {
                Object retVal = method.invoke(actualTarget, args);

                // If return value is a Statement, apply transaction timeout.
                // Applies to createStatement, prepareStatement, prepareCall.
                if (retVal instanceof Statement) {
                    DataSourceUtils.applyTransactionTimeout((Statement) retVal, this.targetDataSource);
                }

                return retVal;
            }
            catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
            finally {
                if (actualTarget != this.target) {
                    DataSourceUtils.doReleaseConnection(actualTarget, this.targetDataSource);
                }
            }
        }
    }

}
