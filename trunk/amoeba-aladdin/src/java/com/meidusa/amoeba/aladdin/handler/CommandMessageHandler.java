package com.meidusa.amoeba.aladdin.handler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import com.meidusa.amoeba.aladdin.io.ResultPacket;
import com.meidusa.amoeba.context.ProxyRuntimeContext;
import com.meidusa.amoeba.mysql.net.MysqlClientConnection;
import com.meidusa.amoeba.net.Connection;
import com.meidusa.amoeba.net.MessageHandler;
import com.meidusa.amoeba.net.Sessionable;
import com.meidusa.amoeba.net.poolable.ObjectPool;

/**
 * 
 * @author struct
 * 
 */
public abstract class CommandMessageHandler implements MessageHandler,
		Sessionable {
	private static Logger logger = Logger
			.getLogger(CommandMessageHandler.class);
	protected MysqlClientConnection source;
	private long createTime;
	private long timeout;
	private long endTime;
	private boolean ended = false;
	private ObjectPool[] pools;
	private final Lock lock = new ReentrantLock(false);
	private Map<java.sql.Connection, ObjectPool> connPoolMap = new HashMap<java.sql.Connection, ObjectPool>();
	private String query;
	private Object parameter;

	protected static abstract class QueryRunnable implements Runnable {
		private java.sql.Connection conn;
		protected Object parameter;
		protected CountDownLatch latch;
		protected ResultPacket packet;
		protected String query;
		protected MysqlClientConnection source;

		QueryRunnable(CountDownLatch latch, java.sql.Connection conn,
				String query, Object parameter, ResultPacket packet) {
			this.conn = conn;
			this.parameter = parameter;
			this.packet = packet;
			this.query = query;
			this.latch = latch;
		}

		public void init(CommandMessageHandler handler) {
			this.source = handler.source;
		}

		protected static boolean isSelect(String query) {
			char ch = query.trim().charAt(0);
			if (ch == 's' || ch == 'S') {
				return true;
			} else {
				return false;
			}
		}

		/**
		 * Connection ����end session �з��ص�pool��
		 * 
		 * @param conn
		 */
		protected abstract void doRun(java.sql.Connection conn);

		public void run() {
			try {
				try {
					doRun(conn);
					
				} catch (Exception e) {
					logger.error("run query error:", e);
				}
			} finally {
				latch.countDown();
			}
		}
	}

	public CommandMessageHandler(MysqlClientConnection source, String query,
			Object parameter, ObjectPool[] pools, long timeout) {
		this.source = source;
		this.query = query;
		this.pools = pools;
		this.timeout = timeout;
		this.parameter = parameter;
	}

	public void handleMessage(Connection conn, byte[] message) {
		// TODO Auto-generated method stub

	}

	public boolean checkIdle(long now) {
		if (timeout > 0) {
			return (now - createTime) > timeout;
		} else {
			if (ended) {
				/**
				 * �����session�Ѿ���������ʱ���serverConnection�˻��ڵȴ��������ݷ��ʡ����ҳ���15s,
				 * ����Ҫ�����еĻỰ
				 * �������ڸ���ԭ����ɷ�������û�з������ݻ����Ѿ������ĻỰ��ServerConnection�޷�����Pool�С�
				 */
				return (now - endTime) > 15000;
			}
			return false;
		}
	}

	public void startSession() throws Exception {
		final CountDownLatch latch = new CountDownLatch(pools.length);
		ResultPacket packet = newResultPacket(query);
		for (ObjectPool pool : pools) {
			final java.sql.Connection conn = (java.sql.Connection) pool
					.borrowObject();
			connPoolMap.put(conn, pool);
			QueryRunnable runnable = newQueryRunnable(latch, conn, query,
					parameter, packet);
			runnable.init(this);
			ProxyRuntimeContext.getInstance().getClientSideExecutor().execute(
					runnable);
		}

		if (timeout > 0) {
			latch.await(timeout, TimeUnit.MILLISECONDS);
		} else {
			latch.await();
		}
		endSession();
		packet.wirteToConnection(source);
	}

	protected abstract ResultPacket newResultPacket(String query);

	protected abstract QueryRunnable newQueryRunnable(CountDownLatch latch,
			java.sql.Connection conn, String query2, Object parameter,
			ResultPacket packet);

	public void endSession() {
		if (isEnded())
			return;
		lock.lock();
		try {
			if (!ended) {
				endTime = System.currentTimeMillis();
				ended = true;
				for (Map.Entry<java.sql.Connection, ObjectPool> entry : connPoolMap
						.entrySet()) {
					try {
						entry.getValue().returnObject(entry.getKey());
					} catch (Exception e) {
						logger.error("return connection to pool error", e);
					}
				}
			}
		} finally {
			lock.unlock();
		}
	}

	public boolean isEnded() {
		lock.lock();
		try {
			return this.ended;
		} finally {
			lock.unlock();
		}
	}

}