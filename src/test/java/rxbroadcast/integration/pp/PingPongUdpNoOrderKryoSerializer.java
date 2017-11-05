package rxbroadcast.integration.pp;

import rxbroadcast.Broadcast;
import rxbroadcast.KryoSerializer;
import rxbroadcast.NoOrder;
import rxbroadcast.UdpBroadcast;

import org.junit.Test;
import rx.Observable;
import rx.observers.TestSubscriber;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

public final class PingPongUdpNoOrderKryoSerializer {
    private static final int MESSAGE_COUNT = 100;

    private static final long TIMEOUT = 30;

    /**
     * Receive a PING and respond with a PONG.
     * @throws SocketException if the socket could not be opened, or the socket could not bind to the given port.
     * @throws UnknownHostException if no IP address for the host machine could be found.
     */
    @Test
    public final void recv() throws SocketException, UnknownHostException {
        final int port = Integer.parseInt(System.getProperty("port"));
        final InetSocketAddress destination = new InetSocketAddress(System.getProperty("destination"), port);
        final TestSubscriber<Ping> subscriber = new TestSubscriber<>();
        try (final DatagramSocket socket = new DatagramSocket(port)) {
            final Broadcast broadcast = new UdpBroadcast<>(
                socket, destination, new KryoSerializer<>(), new NoOrder<>());

            broadcast.valuesOfType(Ping.class)
                .doOnNext(System.out::println)
                .concatMap(ping ->
                    broadcast.send(new Pong(ping.value))
                        // Once we've sent the response, we can emit the PING value to the subscriber.
                        // The cast here is a hack to allow us to concatenate a PING onto the stream.
                        // Where this is an `Observable<Void>` we know we won't get anything that needs to be casted.
                        .cast(Ping.class)
                        .concatWith(Observable.just(ping))
                        .doOnCompleted(() -> System.out.println("Sent PONG")))
                .take(MESSAGE_COUNT)
                .subscribe(subscriber);

            subscriber.awaitTerminalEventAndUnsubscribeOnTimeout(TIMEOUT, TimeUnit.SECONDS);
            subscriber.assertNoErrors();
            subscriber.assertValueCount(MESSAGE_COUNT);
        }
    }

    /**
     * Send a set PING messages to the receiver, expecting PONG messages in response.
     * @param args the command line arguments passed to the program
     * @throws SocketException if the socket could not be opened, or the socket could not bind to the given port.
     * @throws UnknownHostException if no IP address for the host machine could be found.
     */
    public static void main(final String[] args) throws InterruptedException, SocketException, UnknownHostException {
        final int port = Integer.parseInt(System.getProperty("port"));
        final InetSocketAddress destination = new InetSocketAddress(System.getProperty("destination"), port);
        try (final DatagramSocket socket = new DatagramSocket(port)) {
            final Broadcast broadcast = new UdpBroadcast<>(
                socket, destination, new KryoSerializer<>(), new NoOrder<>());

            Observable.range(1, MESSAGE_COUNT)
                .map(Ping::new)
                .doOnNext(System.out::println)
                .concatMap(value ->
                    broadcast.send(value)
                        .doOnCompleted(() -> System.out.printf("Sent %s%n", value))
                        .cast(Pong.class)
                        .concatWith(broadcast.valuesOfType(Pong.class).first()))
                .timeout(TIMEOUT, TimeUnit.SECONDS)
                .toBlocking()
                .subscribe(pong ->
                    System.out.printf("Received %s%n", pong));
        }
    }
}