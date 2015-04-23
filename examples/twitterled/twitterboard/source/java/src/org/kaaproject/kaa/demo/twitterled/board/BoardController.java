package org.kaaproject.kaa.demo.twitterled.board;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.kaaproject.kaa.client.configuration.base.ConfigurationListener;
import org.kaaproject.kaa.client.notification.NotificationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BoardController extends Thread implements NotificationListener, ConfigurationListener {
    private static final String LED_MATRIX_COMMAND = "sudo /home/pi/display16x32/rpi-rgb-led-matrix/led-matrix -r 16 -D 1 -m ";
    private static final int DEFAULT_TIMEOUT = 5000;
    private static final String DEFAULT_FILE_NAME = "default.ppm";
    private static final String CUSTOM_FILE_NAME = "text.ppm";
    private static final int MIN_REPEAT_COUNT = 1;
    private static final Logger LOG = LoggerFactory.getLogger(BoardController.class);
    private static final int MAX_MESSAGES_IN_QUEUE = 3;
    private volatile boolean stopped = false;
    private volatile TwitterBoardConfiguration configuration;
    private volatile Process displayProcess;
    private volatile int defaultMessageWidth;

    private final BlockingQueue<TwitterBoardNotification> queue;

    public BoardController(TwitterBoardConfiguration configuration) {
        this.configuration = configuration;
        this.queue = new LinkedBlockingQueue<TwitterBoardNotification>();
    }

    public void run() {
        
        while (!stopped) {
            try {
                if (queue.size() == 0) {
                    startDefaultMessage();
                }

                TwitterBoardNotification notification = queue.take();
                while (queue.size() > MAX_MESSAGES_IN_QUEUE) {
                    LOG.info("Queue size is to big ({}). Skipping message {} ", queue.size(), notification);
                    notification = queue.take();
                }

                int timeout = DEFAULT_TIMEOUT;

                if (notification != null) {
                    LOG.info("Going to display {}", notification);
                    timeout = startMessage(notification);
                    LOG.info("Time required to display this message {} and repeat count is {}", timeout, configuration.getRepeatCount());
                    timeout *= (Math.max(MIN_REPEAT_COUNT, configuration.getRepeatCount()));
                }

                try {
                    LOG.info("Will sleep for {} ms", timeout);
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted: {}", e.getMessage(), e);
                }
            } catch (Exception e) {
                LOG.error("Failed to display message: {}", e.getMessage(), e);
            }
        }
    }

    public void shutdown() {
        stopped = true;
        interrupt();
    }

    @Override
    public void onNotification(String id, TwitterBoardNotification notification) {
        LOG.info("Notification for topic id [{}] received.", id);
        LOG.info("Notification body: {}", notification.getMessage());
        try {
            this.queue.put(notification);
        } catch (InterruptedException e) {
            LOG.info("Failed to put notification to queue {}", notification.getMessage(), e);
        }
    }

    @Override
    public void onConfigurationUpdate(TwitterBoardConfiguration configuration) {
        LOG.info("Configuration body: {}", configuration);
        this.configuration = configuration;
    }

    private void startDefaultMessage() {
        try {
            displayMessage(configuration.getDefaultMessage(), null, null, DEFAULT_FILE_NAME);
        } catch (Exception e) {
            LOG.error("Failed to display default message: {}", e.getMessage(), e);
        }
    }

    private int startMessage(TwitterBoardNotification notification) throws Exception {
        return displayMessage(notification.getMessage(), notification.getKeywords(), notification.getAuthor(), CUSTOM_FILE_NAME);
    }

    private int displayMessage(String messageSrc, List<String> keywords, String author, String fileName) throws Exception {
        TwitterMessage message = new TwitterMessage(messageSrc, author, keywords);
        List<TwitterMessageToken> tokens = message.toTokens();
        int width;
        if(DEFAULT_FILE_NAME.equals(fileName)){
            if(defaultMessageWidth == 0){
                defaultMessageWidth = PPMFactory.createAndSave(fileName, tokens, toRGB(configuration.getBackgroundColor()));
            }
            width = defaultMessageWidth;
        }else{
            width = PPMFactory.createAndSave(fileName, tokens, toRGB(configuration.getBackgroundColor()));
        }
        if (displayProcess != null) {
            displayProcess.destroy();
        }

        displayProcess = Runtime.getRuntime().exec(LED_MATRIX_COMMAND + " " + configuration.getScrollSpeed() + " " + fileName);

        Thread loggintThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    String line;
                    BufferedReader bri = new BufferedReader(new InputStreamReader(displayProcess.getInputStream()));
                    BufferedReader bre = new BufferedReader(new InputStreamReader(displayProcess.getErrorStream()));
                    while ((line = bri.readLine()) != null) {
                        LOG.info("Matrix output: {}", line);
                    }
                    bri.close();
                    while ((line = bre.readLine()) != null) {
                        LOG.info("Matrix error: {}", line);
                    }
                    bre.close();
                } catch (Exception e) {
                    LOG.warn("Failed to monitor process: {}", e.getMessage());
                }
            }
        });
        loggintThread.start();
        return width * configuration.getScrollSpeed();
    }

    private class TwitterMessage {
        private final String message;
        private final String author;
        private final List<String> keywords;

        public TwitterMessage(String message, String author, List<String> keywords) {
            super();
            this.message = message;
            this.author = author;
            if (keywords != null) {
                this.keywords = keywords;
            } else {
                this.keywords = Collections.emptyList();
            }
        }

        public List<TwitterMessageToken> toTokens() {
            List<TwitterMessageToken> result = new ArrayList<BoardController.TwitterMessageToken>();
            if (author != null) {
                result.add(new TwitterMessageToken("@" + author, toRGB(configuration.getAtTagsColor())));
            }

            String[] tokens = message.split("\\s+");
            for (String token : tokens) {
                boolean isKeyword = false;
                for (String keyword : keywords) {
                    if (token.equals(keyword)) {
                        isKeyword = true;
                        break;
                    }
                }
                if (isKeyword) {
                    result.add(new TwitterMessageToken(token, toRGB(configuration.getKeywordsColor())));
                } else if (token.startsWith("#")) {
                    result.add(new TwitterMessageToken(token, toRGB(configuration.getHashTagsColor())));
                } else if (token.startsWith("@")) {
                    result.add(new TwitterMessageToken(token, toRGB(configuration.getAtTagsColor())));
                } else {
                    result.add(new TwitterMessageToken(token, toRGB(configuration.getTextColor())));
                }
            }

            return result;
        }

    }

    public static class TwitterMessageToken {
        private final String token;
        private final int color;

        public TwitterMessageToken(String token, int color) {
            super();
            this.token = token;
            this.color = color;
        }

        public String getToken() {
            return token;
        }

        public int getColor() {
            return color;
        }
    }

    private static int toRGB(String color) {
        return Color.decode(color).getRGB();
    }

}
