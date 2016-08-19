/**
 * Project: zebra-client
 *
 * File Created at Feb 19, 2014
 *
 */
package com.dianping.zebra.group.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dianping.zebra.filter.JdbcFilter;
import com.dianping.zebra.single.jdbc.SingleConnection;
import com.dianping.zebra.util.JDBCUtils;
import com.dianping.zebra.util.SqlType;
import com.dianping.zebra.util.SqlUtils;

/**
 * @author Leo Liang
 * @author hao.zhu
 */
public class GroupStatement implements Statement {

	protected GroupConnection groupConnection;

	protected List<JdbcFilter> filters;

	protected Statement innerStatement = null;

	protected GroupResultSet currentResultSet = null;

	protected List<String> batchedSqls;

	protected boolean closed = false;

	protected int fetchSize;

	protected int maxRows;

	protected boolean moreResults = false;

	protected int queryTimeout = 0;

	protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;

	protected int resultSetHoldability = -1;

	protected int resultSetType = ResultSet.TYPE_FORWARD_ONLY;

	protected int updateCount = -1;

	public GroupStatement(GroupConnection connection, List<JdbcFilter> filters) {
		this.groupConnection = connection;
		this.filters = filters;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		checkClosed();

		if (batchedSqls == null) {
			batchedSqls = new ArrayList<String>();
		}
		if (sql != null) {
			batchedSqls.add(sql);
		}
	}

	@Override
	public void cancel() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support cancel");
	}

	protected void checkClosed() throws SQLException {
		if (closed) {
			throw new SQLException("No operations allowed after statement closed.");
		}
	}

	@Override
	public void clearBatch() throws SQLException {
		checkClosed();
		if (batchedSqls != null) {
			batchedSqls.clear();
		}
	}

	@Override
	public void clearWarnings() throws SQLException {
		checkClosed();
		if (innerStatement != null) {
			innerStatement.clearWarnings();
		}
	}

	@Override
	public void close() throws SQLException {
		if (closed) {
			return;
		}
		closed = true;

		try {
			if (currentResultSet != null) {
				currentResultSet.close();
			}
		} finally {
			currentResultSet = null;
		}

		try {
			if (this.innerStatement != null) {
				this.innerStatement.close();
			}
		} finally {
			this.innerStatement = null;
		}
	}

	protected void closeCurrentResultSet() throws SQLException {
		if (currentResultSet != null) {
			try {
				currentResultSet.close();
			} catch (SQLException e) {
				// ignore it
			} finally {
				currentResultSet = null;
			}
		}
	}

	public void closeOnCompletion() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support closeOnCompletion");
	}

	private Statement createInnerStatement(Connection conn, boolean isBatch) throws SQLException {
		Statement stmt;
		if (isBatch) {
			stmt = conn.createStatement();
		} else {
			int tmpResultSetHoldability = this.resultSetHoldability;
			if (tmpResultSetHoldability == -1) {
				tmpResultSetHoldability = conn.getHoldability();
			}

			stmt = conn.createStatement(this.resultSetType, this.resultSetConcurrency, tmpResultSetHoldability);
		}

		stmt.setQueryTimeout(queryTimeout);
		stmt.setFetchSize(fetchSize);
		stmt.setMaxRows(maxRows);

		setInnerStatement(stmt);
		return stmt;
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		return executeInternal(sql, -1, null, null);
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		return executeInternal(sql, autoGeneratedKeys, null, null);
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		return executeInternal(sql, -1, columnIndexes, null);
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		return executeInternal(sql, -1, null, columnNames);
	}

	@Override
	public int[] executeBatch() throws SQLException {
		try {
			checkClosed();
			closeCurrentResultSet();

			if (batchedSqls == null || batchedSqls.isEmpty()) {
				return new int[0];
			}

			Connection conn = this.groupConnection.getRealConnection(null, true);
			return executeBatchOnConnection(conn, batchedSqls);
		} finally {
			if (batchedSqls != null) {
				batchedSqls.clear();
			}
		}
	}

	private int[] executeBatchOnConnection(final Connection conn, final List<String> batchedSqls) throws SQLException {
		Statement stmt = createInnerStatement(conn, true);
		for (String sql : batchedSqls) {
			stmt.addBatch(sql);
		}
		return stmt.executeBatch();
	}

	private boolean executeInternal(String sql, int autoGeneratedKeys, int[] columnIndexes, String[] columnNames)
			throws SQLException {
		SqlType sqlType = SqlUtils.getSqlType(sql);
		if (sqlType.isQuery()) {
			executeQuery(sql);
			return true;
		} else {
			if (autoGeneratedKeys == -1 && columnIndexes == null && columnNames == null) {
				executeUpdate(sql);
			} else if (autoGeneratedKeys != -1) {
				executeUpdate(sql, autoGeneratedKeys);
			} else if (columnIndexes != null) {
				executeUpdate(sql, columnIndexes);
			} else if (columnNames != null) {
				executeUpdate(sql, columnNames);
			} else {
				executeUpdate(sql);
			}

			return false;
		}
	}

	@Override
	public ResultSet executeQuery(final String sql) throws SQLException {
		checkClosed();
		closeCurrentResultSet();

		Connection conn = this.groupConnection.getRealConnection(sql, false);

		return executeQueryOnConnection(conn, sql);
	}

	private ResultSet executeQueryOnConnection(Connection conn, String sql) throws SQLException {
		Statement stmt = createInnerStatement(conn, false);
		currentResultSet = new GroupResultSet(stmt.executeQuery(sql));
		return currentResultSet;
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		return executeUpdateInternal(sql, -1, null, null);
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		return executeUpdateInternal(sql, autoGeneratedKeys, null, null);
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		return executeUpdateInternal(sql, -1, columnIndexes, null);
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		return executeUpdateInternal(sql, -1, null, columnNames);
	}

	private int executeUpdateInternal(final String sql, final int autoGeneratedKeys, final int[] columnIndexes,
			final String[] columnNames) throws SQLException {
		checkClosed();
		closeCurrentResultSet();

		Connection conn = this.groupConnection.getRealConnection(sql, true);

		try {
			updateCount = executeUpdateOnConnection(conn, sql, autoGeneratedKeys, columnIndexes, columnNames);
		} catch (SQLException e) {
			if (conn instanceof SingleConnection) {
				((SingleConnection) conn).getDataSource().getPunisher().countAndPunish(e);
			}
			JDBCUtils.throwWrappedSQLException(e);
		}

		return updateCount;
	}

	private int executeUpdateOnConnection(Connection conn, String sql, int autoGeneratedKeys, int[] columnIndexes,
			String[] columnNames) throws SQLException {
		Statement stmt = createInnerStatement(conn, false);

		if (autoGeneratedKeys == -1 && columnIndexes == null && columnNames == null) {
			return stmt.executeUpdate(sql);
		} else if (autoGeneratedKeys != -1) {
			return stmt.executeUpdate(sql, autoGeneratedKeys);
		} else if (columnIndexes != null) {
			return stmt.executeUpdate(sql, columnIndexes);
		} else if (columnNames != null) {
			return stmt.executeUpdate(sql, columnNames);
		} else {
			return stmt.executeUpdate(sql);
		}
	}

	@Override
	public Connection getConnection() throws SQLException {
		return this.groupConnection;
	}

	@Override
	public int getFetchDirection() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support getFetchDirection");
	}

	@Override
	public void setFetchDirection(int direction) throws SQLException {
		throw new UnsupportedOperationException("zebra does not support setFetchDirection");
	}

	@Override
	public int getFetchSize() throws SQLException {
		return this.fetchSize;
	}

	@Override
	public void setFetchSize(int fetchSize) throws SQLException {
		this.fetchSize = fetchSize;
	}

	@Override
	public ResultSet getGeneratedKeys() throws SQLException {
		if (this.innerStatement != null) {
			return new GroupResultSet(this.innerStatement.getGeneratedKeys());
		} else {
			throw new SQLException("No update operations executed before getGeneratedKeys");
		}
	}

	@Override
	public int getMaxFieldSize() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support getMaxFieldSize");
	}

	@Override
	public void setMaxFieldSize(int max) throws SQLException {
		throw new UnsupportedOperationException("zebra does not support setMaxFieldSize");
	}

	@Override
	public int getMaxRows() throws SQLException {
		return maxRows;
	}

	@Override
	public void setMaxRows(int maxRows) throws SQLException {
		this.maxRows = maxRows;
	}

	@Override
	public boolean getMoreResults() throws SQLException {
		return moreResults;
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException {
		throw new UnsupportedOperationException("zebra does not support getMoreResults");
	}

	@Override
	public int getQueryTimeout() throws SQLException {
		return queryTimeout;
	}

	@Override
	public void setQueryTimeout(int queryTimeout) throws SQLException {
		this.queryTimeout = queryTimeout;
	}

	@Override
	public ResultSet getResultSet() throws SQLException {
		return currentResultSet;
	}

	@Override
	public int getResultSetConcurrency() throws SQLException {
		return this.resultSetConcurrency;
	}

	public void setResultSetConcurrency(int resultSetConcurrency) {
		this.resultSetConcurrency = resultSetConcurrency;
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		return this.resultSetHoldability;
	}

	public void setResultSetHoldability(int resultSetHoldability) {
		this.resultSetHoldability = resultSetHoldability;
	}

	@Override
	public int getResultSetType() throws SQLException {
		return this.resultSetType;
	}

	public void setResultSetType(int resultSetType) {
		this.resultSetType = resultSetType;
	}

	@Override
	public int getUpdateCount() throws SQLException {
		return this.updateCount;
	}

	@Override
	public SQLWarning getWarnings() throws SQLException {
		checkClosed();
		if (innerStatement != null) {
			return innerStatement.getWarnings();
		}
		return null;
	}

	public boolean isCloseOnCompletion() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support isCloseOnCompletion");
	}

	@Override
	public boolean isClosed() throws SQLException {
		return closed;
	}

	@Override
	public boolean isPoolable() throws SQLException {
		throw new UnsupportedOperationException("zebra does not support isPoolable");
	}

	@Override
	public void setPoolable(boolean poolable) throws SQLException {
		throw new UnsupportedOperationException("zebra does not support setPoolable");
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return this.getClass().isAssignableFrom(iface);
	}

	@Override
	public void setCursorName(String name) throws SQLException {
		throw new UnsupportedOperationException("setCursorName");
	}

	@Override
	public void setEscapeProcessing(boolean enable) throws SQLException {
		throw new UnsupportedOperationException("setEscapeProcessing");
	}

	void setInnerStatement(Statement innerStatement) {
		if (this.innerStatement != null) {
			try {
				this.innerStatement.close();
			} catch (SQLException e) {
				// ignore it
			}
		}
		this.innerStatement = innerStatement;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		try {
			return (T) this;
		} catch (Exception e) {
			throw new SQLException(e);
		}
	}

}