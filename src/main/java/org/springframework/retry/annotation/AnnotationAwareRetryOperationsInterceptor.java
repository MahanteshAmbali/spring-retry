/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.retry.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.IntroductionInterceptor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.backoff.UniformRandomBackOffPolicy;
import org.springframework.retry.interceptor.MethodArgumentsKeyGenerator;
import org.springframework.retry.interceptor.MethodInvocationRecoverer;
import org.springframework.retry.interceptor.NewMethodArgumentsIdentifier;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.policy.CircuitBreakerRetryPolicy;
import org.springframework.retry.policy.MapRetryContextCache;
import org.springframework.retry.policy.RetryContextCache;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

/**
 * WrappeMethodInterceptorr interceptor that interprets the retry metadata on the method it is invoking and
 * delegates to an appropriate RetryOperationsInterceptor.
 *
 * @author Dave Syer
 * @author Artem Bilan
 * @since 1.1
 *
 */
public class AnnotationAwareRetryOperationsInterceptor implements IntroductionInterceptor, BeanFactoryAware {

	private final Map<Method, MethodInterceptor> delegates = new HashMap<Method, MethodInterceptor>();

	private RetryContextCache retryContextCache = new MapRetryContextCache();

	private MethodArgumentsKeyGenerator methodArgumentsKeyGenerator;

	private NewMethodArgumentsIdentifier newMethodArgumentsIdentifier;

	private Sleeper sleeper;

	private BeanFactory beanFactory;

	/**
	 * @param sleeper the sleeper to set
	 */
	public void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	/**
	 * Public setter for the {@link RetryContextCache}.
	 *
	 * @param retryContextCache the {@link RetryContextCache} to set.
	 */
	public void setRetryContextCache(RetryContextCache retryContextCache) {
		this.retryContextCache = retryContextCache;
	}

	/**
	 * @param methodArgumentsKeyGenerator the {@link MethodArgumentsKeyGenerator}
	 */
	public void setKeyGenerator(MethodArgumentsKeyGenerator methodArgumentsKeyGenerator) {
		this.methodArgumentsKeyGenerator = methodArgumentsKeyGenerator;
	}

	/**
	 * @param newMethodArgumentsIdentifier the {@link NewMethodArgumentsIdentifier}
	 */
	public void setNewItemIdentifier(NewMethodArgumentsIdentifier newMethodArgumentsIdentifier) {
		this.newMethodArgumentsIdentifier = newMethodArgumentsIdentifier;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public boolean implementsInterface(Class<?> intf) {
		return org.springframework.retry.interceptor.Retryable.class.isAssignableFrom(intf);
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		MethodInterceptor delegate = getDelegate(invocation.getThis(), invocation.getMethod());
		if (delegate != null) {
			return delegate.invoke(invocation);
		}
		else {
			return invocation.proceed();
		}
	}

	private MethodInterceptor getDelegate(Object target, Method method) {
		if (!this.delegates.containsKey(method)) {
			synchronized (this.delegates) {
				if (!this.delegates.containsKey(method)) {
					Retryable retryable = AnnotationUtils.findAnnotation(method, Retryable.class);
					if (retryable == null) {
						retryable = AnnotationUtils.findAnnotation(method.getDeclaringClass(), Retryable.class);
					}
					if (retryable == null) {
						return this.delegates.put(method, null);
					}
					MethodInterceptor delegate;
					if (StringUtils.hasText(retryable.interceptor())) {
						delegate = this.beanFactory.getBean(retryable.interceptor(), MethodInterceptor.class);
					}
					else if (retryable.stateful()) {
						delegate = getStatefulInterceptor(target, method, retryable);
					}
					else {
						delegate = getStatelessInterceptor(target, method, retryable);
					}
					this.delegates.put(method, delegate);
				}
			}
		}
		return this.delegates.get(method);
	}

	private MethodInterceptor getStatelessInterceptor(Object target, Method method, Retryable retryable) {
		return RetryInterceptorBuilder.stateless()
				.retryPolicy(getRetryPolicy(retryable))
				.backOffPolicy(getBackoffPolicy(retryable.backoff()))
				.recoverer(getRecoverer(target, method))
				.build();
	}

	private MethodInterceptor getStatefulInterceptor(Object target, Method method, Retryable retryable) {
		RetryTemplate template = new RetryTemplate();
		template.setRetryContextCache(this.retryContextCache);

		CircuitBreaker circuit = AnnotationUtils.findAnnotation(method, CircuitBreaker.class);
		if (circuit!=null) {
			RetryPolicy policy = getRetryPolicy(circuit);
			CircuitBreakerRetryPolicy breaker = new CircuitBreakerRetryPolicy(policy);
			breaker.setOpenTimeout(circuit.openTimeout());
			breaker.setResetTimeout(circuit.resetTimeout());
			template.setRetryPolicy(breaker);
			template.setBackOffPolicy(new NoBackOffPolicy());
			String label = circuit.label();
			if (!StringUtils.hasText(label))  {
				label = method.toGenericString();
			}
			return RetryInterceptorBuilder.circuitBreaker()
					.retryOperations(template)
					.recoverer(getRecoverer(target, method))
					.label(label)
					.build();
		}
		RetryPolicy policy = getRetryPolicy(retryable);
		template.setRetryPolicy(policy);
		template.setBackOffPolicy(getBackoffPolicy(retryable.backoff()));
		return RetryInterceptorBuilder.stateful()
				.retryOperations(template)
				.recoverer(getRecoverer(target, method))
				.keyGenerator(this.methodArgumentsKeyGenerator)
				.newMethodArgumentsIdentifier(this.newMethodArgumentsIdentifier)
				.build();
	}

	private MethodInvocationRecoverer<?> getRecoverer(Object target, Method method) {
		if (target instanceof MethodInvocationRecoverer) {
			return (MethodInvocationRecoverer<?>) target;
		}
		final AtomicBoolean foundRecoverable = new AtomicBoolean(false);
		ReflectionUtils.doWithMethods(target.getClass(), new MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException,
					IllegalAccessException {
				if (AnnotationUtils.findAnnotation(method, Recover.class) != null) {
					foundRecoverable.set(true);
				}
			}
		});

		if (!foundRecoverable.get()) {
			return null;
		}
		return new RecoverAnnotationRecoveryHandler<Object>(target, method);
	}

	private RetryPolicy getRetryPolicy(Annotation retryable) {
		Map<String, Object> attrs = AnnotationUtils.getAnnotationAttributes(retryable);
		@SuppressWarnings("unchecked")
		Class<? extends Throwable>[] includes = (Class<? extends Throwable>[]) attrs.get("value");
		if (includes.length == 0) {
			@SuppressWarnings("unchecked")
			Class<? extends Throwable>[] value = (Class<? extends Throwable>[]) attrs.get("include");
			includes = value;
		}
		@SuppressWarnings("unchecked")
		Class<? extends Throwable>[] excludes = (Class<? extends Throwable>[]) attrs.get("exclude");
		if (includes.length == 0 && excludes.length == 0) {
			SimpleRetryPolicy simple = new SimpleRetryPolicy();
			simple.setMaxAttempts((Integer) attrs.get("maxAttempts"));
			return simple;
		}
		Map<Class<? extends Throwable>, Boolean> policyMap = new HashMap<Class<? extends Throwable>, Boolean>();
		for (Class<? extends Throwable> type : includes) {
			policyMap.put(type, true);
		}
		for (Class<? extends Throwable> type : excludes) {
			policyMap.put(type, false);
		}
		return new SimpleRetryPolicy((Integer) attrs.get("maxAttempts"), policyMap, true);
	}

	private BackOffPolicy getBackoffPolicy(Backoff backoff) {
		long min = backoff.delay() == 0 ? backoff.value() : backoff.delay();
		long max = backoff.maxDelay();
		if (backoff.multiplier() > 0) {
			ExponentialBackOffPolicy policy = new ExponentialBackOffPolicy();
			if (backoff.random()) {
				policy = new ExponentialRandomBackOffPolicy();
			}
			policy.setInitialInterval(min);
			policy.setMultiplier(backoff.multiplier());
			policy.setMaxInterval(max > min ? max : ExponentialBackOffPolicy.DEFAULT_MAX_INTERVAL);
			if (this.sleeper != null) {
				policy.setSleeper(this.sleeper);
			}
			return policy;
		}
		if (max > min) {
			UniformRandomBackOffPolicy policy = new UniformRandomBackOffPolicy();
			policy.setMinBackOffPeriod(min);
			policy.setMaxBackOffPeriod(max);
			if (this.sleeper != null) {
				policy.setSleeper(this.sleeper);
			}
			return policy;
		}
		FixedBackOffPolicy policy = new FixedBackOffPolicy();
		policy.setBackOffPeriod(min);
		if (this.sleeper != null) {
			policy.setSleeper(this.sleeper);
		}
		return policy;
	}

}
