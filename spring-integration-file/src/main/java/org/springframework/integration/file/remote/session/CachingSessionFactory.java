/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.file.remote.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.integration.util.SimplePool;

/**
 * A {@link SessionFactory} implementation that caches Sessions for reuse without
 * requiring reconnection each time the Session is retrieved from the factory.
 * This implementation wraps and delegates to a target SessionFactory instance.
 *
 * @author Josh Long
 * @author Oleg Zhurakousky
 * @author Mark Fisher
 * @author Gary Russell
 * @since 2.0
 */
public class CachingSessionFactory<F> implements SessionFactory<F>, DisposableBean {

	private static final Log logger = LogFactory.getLog(CachingSessionFactory.class);

	private final SessionFactory<F> sessionFactory;

	private final SimplePool<Session<F>> pool;

	private final boolean isSharedSessionCapable;

	private volatile long sharedSessionEpoch;

	/**
	 * Create a CachingSessionFactory with an unlimited number of sessions.
	 *
	 * @param sessionFactory the underlying session factory.
	 */
	public CachingSessionFactory(SessionFactory<F> sessionFactory) {
		this(sessionFactory, 0);
	}

	/**
	 * Create a CachingSessionFactory with the specified session limit. By default, if
	 * no sessions are available in the cache, and the size limit has been reached,
	 * calling threads will block until a session is available.
	 * @see #setSessionWaitTimeout(long)
	 * @see #setPoolSize(int)
	 *
	 * @param sessionFactory The underlying session factory.
	 * @param sessionCacheSize The maximum cache size.
	 */
	public CachingSessionFactory(SessionFactory<F> sessionFactory, int sessionCacheSize) {
		this.sessionFactory = sessionFactory;
		this.pool = new SimplePool<Session<F>>(sessionCacheSize, new SimplePool.PoolItemCallback<Session<F>>() {
			@Override
			public Session<F> createForPool() {
				return CachingSessionFactory.this.sessionFactory.getSession();
			}

			@Override
			public boolean isStale(Session<F> session) {
				return !session.isOpen();
			}

			@Override
			public void removedFromPool(Session<F> session) {
				session.close();
			}
		});
		this.isSharedSessionCapable = sessionFactory instanceof SharedSessionCapable;
	}


	/**
	 * Sets the limit of how long to wait for a session to become available.
	 *
	 * @param sessionWaitTimeout the session wait timeout.
	 * @throws IllegalStateException if the wait expires prior to a Session becoming available.
	 */
	public void setSessionWaitTimeout(long sessionWaitTimeout) {
		this.pool.setWaitTimeout(sessionWaitTimeout);
	}

	/**
	 * Modify the target session pool size; the actual pool size will adjust up/down
	 * to this size as and when sessions are requested or retrieved.
	 *
	 * @param poolSize The pool size.
	 */
	public void setPoolSize(int poolSize) {
		this.pool.setPoolSize(poolSize);
	}

	/**
	 * Get a session from the pool (or block if none available).
	 */
	@Override
	public Session<F> getSession() {
		return new CachedSession(this.pool.getItem(), this.sharedSessionEpoch);
	}

	/**
	 * Remove (close) any unused sessions in the pool.
	 */
	@Override
	public void destroy() {
		this.pool.removeAllIdleItems();
	}

	/**
	 * Clear the cache of sessions; also any in-use sessions will be closed when
	 * returned to the cache.
	 */
	public synchronized void resetCache() {
		if (logger.isDebugEnabled()) {
			logger.debug("Cache reset; idle sessions will be removed, in-use sessions will be closed when returned");
		}
		if (this.isSharedSessionCapable && ((SharedSessionCapable) this.sessionFactory).isSharedSession()) {
			((SharedSessionCapable) this.sessionFactory).resetSharedSession();
		}
		long sharedSessionEpoch = System.nanoTime();
		/*
		 * Spin until we get a new value - nano precision but may be lower resolution.
		 * We reset the epoch AFTER resetting the shared session so there is no possibility
		 * of an "old" session being created in the new epoch. There is a slight possibility
		 * that a "new" session might appear in the old epoch and thus be closed when returned to
		 * the cache.
		 */
		while (sharedSessionEpoch == this.sharedSessionEpoch) {
			sharedSessionEpoch = System.nanoTime();
		}
		this.sharedSessionEpoch = sharedSessionEpoch;
		this.pool.removeAllIdleItems();
	}

	public class CachedSession implements Session<F> {

		private final Session<F> targetSession;

		private boolean released;

		private boolean dirty;

		/**
		 * The epoch in which this session was created.
		 */
		private final long sharedSessionEpoch;

		private CachedSession(Session<F> targetSession, long sharedSessionEpoch) {
			this.targetSession = targetSession;
			this.sharedSessionEpoch = sharedSessionEpoch;
		}

		@Override
		public synchronized void close() {
			if (released) {
				if (logger.isDebugEnabled()){
					logger.debug("Session " + targetSession + " already released.");
				}
			}
			else {
				if (logger.isDebugEnabled()){
					logger.debug("Releasing Session " + targetSession + " back to the pool.");
				}
				if (this.sharedSessionEpoch != CachingSessionFactory.this.sharedSessionEpoch) {
					if (logger.isDebugEnabled()){
						logger.debug("Closing session " + targetSession + " after reset.");
					}
					this.targetSession.close();
				}
				else if (this.dirty) {
					this.targetSession.close();
				}
				pool.releaseItem(targetSession);
				released = true;
			}
		}

		@Override
		public boolean remove(String path) throws IOException{
			return this.targetSession.remove(path);
		}

		@Override
		public F[] list(String path) throws IOException{
			return this.targetSession.list(path);
		}

		@Override
		public void read(String source, OutputStream os) throws IOException{
			this.targetSession.read(source, os);
		}

		@Override
		public void write(InputStream inputStream, String destination) throws IOException{
			this.targetSession.write(inputStream, destination);
		}

		@Override
		public boolean isOpen() {
			return this.targetSession.isOpen();
		}

		@Override
		public void rename(String pathFrom, String pathTo) throws IOException {
			this.targetSession.rename(pathFrom, pathTo);
		}

		@Override
		public boolean mkdir(String directory) throws IOException {
			return this.targetSession.mkdir(directory);
		}

		@Override
		public boolean exists(String path) throws IOException{
			return this.targetSession.exists(path);
		}

		@Override
		public String[] listNames(String path) throws IOException {
			return this.targetSession.listNames(path);
		}

		@Override
		public InputStream readRaw(String source) throws IOException {
			return this.targetSession.readRaw(source);
		}

		@Override
		public boolean finalizeRaw() throws IOException {
			return this.targetSession.finalizeRaw();
		}

		public void dirty() {
			this.dirty = true;
		}

	}

}
