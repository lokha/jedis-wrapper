package ua.lokha.jediswrapper;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.util.Pool;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Обертка над {@link BinaryJedisPubSub}, использование которой даст следующие преимущества:
 * <ul>
 *     <li>Экономия потоков. Каждая подписка {@link BinaryJedisPubSub} блокирует для своей работы целый поток.
 *          Добавлять простушиваемые каналы к существующей подписки можно с помощью {@link BinaryJedisPubSub#subscribe(byte[]...)},
 *          но это неудобно. Большая часть разработчиков вообще не вникает в эту особенность и каждый раз создает новую подписку
 *          {@link BinaryJedisPubSub} для каждого канала. Обертка же внутри себя создает общую подписку {@link BinaryJedisPubSub},
 *          которая будет использоваться для всех каналов. Добавлять и удалять простушиваемые каналы можно легко с помощью
 *          методов {@link #subscribe(byte[], BinaryJedisPubSubListener)} и {@link #unsubscribe(BinaryJedisPubSubListener)}.
 *          Внутри обертки создается отдельный поток для общей подписки {@link BinaryJedisPubSub}, который будет блокироваться
 *          вместо потока, в котором создается эта обертка.
 *     </li>
 *     <li>Потокобезопасность. Обертка сделана максимально потокобезопасно, насколько это позволяла сделать библиотека
 *     Jedis.</li>
 *     <li>Устойчивость. В отличии от {@link BinaryJedisPubSub}, подписка в обертке продолжит свою работу в
 *     случае возникновения исключения во время обработки сообщения в {@link BinaryJedisPubSubListener}. Исключение
 *     будет записано в лог.</li>
 *     <li>Возобновления работы подписки в случае ее завершения с ошибкой, например, от потери соединения с redis-сервером.
 *     Если внутренняя подписка {@link BinaryJedisPubSub} завершит свою работу с ошибкой, тогда будет создана новая подписка
 *     {@link BinaryJedisPubSub} с новым соединением {@link Jedis}. Все подписанные каналы будут заново зарегистрированы
 *     в новой подписке.</li>
 * </ul>
 *
 *
 * <p>Эта обретка является ресурсом. После завершения работы с ней, следует вызвать {@link #close()}.
 */
@Log
public class BinaryJedisPubSubWrapper implements AutoCloseable {

    /**
     * Канал-загрушка. В библиотеке Jedis для создания и работы подписки {@link BinaryJedisPubSub}
     * нужен минимум один канал, иначе будет ошибка.
     */
    private static final byte[] dummyChannel = "jedis-dummy-fm2555tz7e".getBytes(StandardCharsets.UTF_8);

    /**
     * Используется для поддержки многопоточности.
     */
    private final Lock lock = new ReentrantLock();
    private final Condition subscribed = lock.newCondition();

    /**
     * Используется для пометки этого ресурса как закрытого.
     */
    @Getter
    private boolean closed = false;

    /**
     * Поток, в котором работает подписка.
     */
    @Getter
    private Thread thread;

    /**
     * Объект подписки Jedis, на основе которого работает обертка.
     */
    @Getter
    private BinaryJedisPubSub pubSub;

    /**
     * Все подписки, ключем выступает имя канала, в значении список слушателей.
     */
    @Getter
    private Map<ByteArrayWrapper, Set<BinaryJedisPubSubListener>> subscribes = new HashMap<>();

    /**
     * Пул для получения соединения {@link Jedis}, служит для инициализации подписки.
     * А так же для возобновления соединения в случае ее обрыва.
     */
    @Getter
    private Pool<Jedis> pool;

    /**
     * Executor, в котором будут обрабатываться сообщения, которые приходят на подписанные каналы.
     */
    @Getter
    private Executor executor;

    /**
     * Счетчик, сколько раз соединение подписка была зарегистрирована.
     * Увеличивается в случае первой подписки и последующих, если подписка будет обрываться.
     */
    @Getter
    private int resubscribeCount = 0;

    /**
     * Конструктор блокирует поток, пока подписка не будет полностью создана и готова к использованию.
     * После завершения работы конструктора можно сразу же подписываться на канал с помощью метода
     * {@link #subscribe(byte[], BinaryJedisPubSubListener)}.
     *
     * @param pool пул соединений с Redis.
     *             Поскольку эта обертка умеет возобновлять подписку в случае ошибки, нужен именно пул соединений,
     *             а не конкретное соединиение.
     * @param executor обработчик, в котором будет вызываться обработка сообщений, приходящих на канал подписки.
     *                 Метод слушателя {@link BinaryJedisPubSubListener#onMessage(byte[], byte[])} будет вызываться
     *                 именно в этом обработчике.
     */
    @SneakyThrows
    public BinaryJedisPubSubWrapper(Pool<Jedis> pool, Executor executor) {
        this.pool = pool;
        this.executor = executor;

        thread = new Thread(() -> {
            while (!(closed || Thread.currentThread().isInterrupted() || pool.isClosed())) {
                try (Jedis jedis = pool.getResource()) {
                    lock.lock();
                    byte[][] channels;
                    try {
                        pubSub = new BinaryJedisPubSub() {
                            @Override
                            public void onMessage(byte[] channel, byte[] message) {
                                callListeners(channel, message);
                            }

                            @Override
                            public void onSubscribe(byte[] channel, int subscribedChannels) {
                                if (Arrays.equals(channel, dummyChannel)) {
                                    resubscribeCount++;
                                    if (resubscribeCount > 1) {
                                        // вызывается в случае повторной регистрации подписки,
                                        // если предыдущая по какой-то причине оборвалась
                                        log.info("Подписка зарегистрирована заново в " + resubscribeCount + " раз.");
                                    }

                                    lock.lock();
                                    try {
                                        subscribed.signalAll();
                                    } finally {
                                        lock.unlock();
                                    }
                                }
                            }
                        };

                        channels = new byte[subscribes.size() + 1][];
                        channels[0] = dummyChannel;
                        int i = 1;
                        for (ByteArrayWrapper channel : subscribes.keySet()) {
                            channels[i++] = channel.getBytes();
                        }
                    } finally {
                        lock.unlock();
                    }
                    jedis.subscribe(pubSub, channels);
                } catch (Exception e) {
                    //noinspection ConstantConditions
                    if (e instanceof InterruptedException) {
                        break;
                    }
                    log.severe("Подписка оборвалась с ошибкой.");
                    e.printStackTrace();
                }
            }

            // на всякий случай, если поток завершил работу в результате ошибки
            close();
        }, this.getClass().getSimpleName() + " Thread");

        thread.start();

        lock.lock();
        try {
            this.awaitSubscribed();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Подписаться на прослушивание канала.
     *
     * @param channel имя канала.
     * @param listener слушатель, который будет вызываться, когда будет приходить сообщение на указанный канал.
     * @return слушатель, переданный параметром {@code listener}. Он выступает индентификатором подписки,
     * с помощью слушателя можно отменить подписку методом {@link #unsubscribe(BinaryJedisPubSubListener)}.
     */
    @SneakyThrows
    public BinaryJedisPubSubListener subscribe(byte[] channel, BinaryJedisPubSubListener listener) {
        lock.lock();
        try {
            this.checkForClosed();
            this.awaitSubscribed();
            subscribes.computeIfAbsent(new ByteArrayWrapper(channel), key -> {
                try {
                    pubSub.subscribe(channel);
                } catch (Exception ignored) {
                    // если будет ошибка, значит подписка оборвалась,
                    // в таком случае канал зарегистрирует при повторной подписке
                }
                return new HashSet<>(1);
            }).add(listener);
            log.info("Подписали на канал " + new String(channel, StandardCharsets.UTF_8) + " listener: " + listener);
            return listener;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Отменить подписку по указанному слушателю.
     *
     * @param listener слушатель, используемый в подписках, которые должны быть отменены.
     *                 Если этот слушатель использовался для прослушивания нескольких каналов, тогда все эти
     *                 подписки будут отменены.
     * @return true, если была отменена хотя бы одна подписка. Если подписок с указанным слушателем не было найдено,
     * тогда вернет false.
     */
    @SneakyThrows
    public boolean unsubscribe(BinaryJedisPubSubListener listener) {
        lock.lock();
        try {
            this.checkForClosed();
            return subscribes.entrySet().removeIf(setEntry -> {
                if (setEntry.getValue().remove(listener)) {
                    log.info("Отписали от канала " + new String(setEntry.getKey().getBytes(), StandardCharsets.UTF_8) +
                        " listener: " + listener);
                    if (setEntry.getValue().isEmpty()) {
                        try {
                            pubSub.unsubscribe(setEntry.getKey().getBytes());
                        } catch (Exception e) {
                            // если будет ошибка, значит подписка оборвалась,
                            // и уже все равно все каналы отписало
                        }
                        return true;
                    }
                }
                return false;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ждать, пока подписка полностью будет создана.
     */
    @SneakyThrows
    private void awaitSubscribed() {
        while (pubSub == null || !pubSub.isSubscribed()) {
            if (!subscribed.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Таймаут ожидания создания подписки pubSub.");
            }
        }
    }

    /**
     * Вызвать обработку сообщения по каналу.
     * Все слущатели указанного канала будут вызваны.
     *
     * @param channel канал.
     * @param message сообщение.
     */
    private void callListeners(byte[] channel, byte[] message) {
        lock.lock();
        try {
            for (BinaryJedisPubSubListener listener : subscribes.getOrDefault(new ByteArrayWrapper(channel), Collections.emptySet())) {
                executor.execute((() -> { // каждое сообщение вызывается в отдельном вызове Executor'a
                    try {
                        listener.onMessage(channel, message);
                    } catch (Exception e) {
                        log.info("Ошибка обработки канала " + Arrays.toString(channel) + " (" + new String(channel) + "), " +
                            "listener: " + listener +
                            ", сообщение: " + Arrays.toString(message) + " (" + new String(message) + ")");
                        e.printStackTrace();
                    }
                }));
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkForClosed() throws IllegalStateException {
        if (closed) {
            throw new IllegalStateException("this resource is closed");
        }
    }

    /**
     * Завершить работу подписки:
     * <ul>
     *     <li>Внутренняя подписка будет отписана с помощью {@link BinaryJedisPubSub#unsubscribe()}.</li>
     *     <li>Поток будет остановлен, который блокировала подписка.</li>
     * </ul>
     *
     * <p>Этот метод является идемпотентным, повторный его вызов не приведет к ошибке, а просто будет проигнорирован.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true; // mark closed
            try {
                if (pubSub != null) {
                    pubSub.unsubscribe();
                }
            } catch (Exception e) {
                log.severe("Unsubscribe " + this.getClass().getSimpleName() + " exception, " +
                    "ignore this exception for idempotency of " + this.getClass().getSimpleName() + ".");
                e.printStackTrace();
            } finally {
                pubSub = null;
            }

            try {
                if (thread != null && !thread.isInterrupted()) {
                    thread.interrupt();
                }
            } catch (Exception e) {
                log.severe("Interrupt thread " + this.getClass().getSimpleName() + " exception, " +
                    "ignore this exception for idempotency of " + this.getClass().getSimpleName() + ".");
                e.printStackTrace();
            } finally {
                thread = null;
            }
        } finally {
            lock.unlock();
        }
    }
}