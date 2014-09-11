/*
 * Copyright (c) 2014 Giorgio Wicklein <giowckln@gmail.com>
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package tradingTools;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IConsole;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IMessage.Type;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;
import java.util.HashSet;
import java.util.Set;

/*
 * This tool places a market order with constant currency risk.
 * Difference here is that the stop loss price is derived from take profit price.
 * Once you define your position currency risk and take profit price,
 * this tool will calculate the right amount (lot size) to meet the defined currency risk.
 * Other nice features, auto stop loss price calculation based on risk:reward 1:1
 * ratio, stop loss move to break even once the price reached 90% of target (TP).
 * Use at your own risk.
 */
public class ConstantRiskSLfromTP implements IStrategy {

    // Configurable parameters
    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Period")
    public Period period = Period.DAILY;
    @Configurable(value = "Buy order",
            description = "Place a BUY order (long)")
    public boolean isBuyOrder = false;
    @Configurable(value = "Sell order",
            description = "Place a SELL order (short)")
    public boolean isSellOrder = false;
    @Configurable(value = "Constant risk amount",
            description = "Constant account currency risk for each trade")
    public int constantCurrencyRisk = 10;
    @Configurable(value = "Take profit price",
            description = "Price of take profit target")
    public double takeProfitPrice = 0;
    @Configurable(value = "B.E. on 90%",
            description = "Move SL to break even once 90% of TP is reached")
    public boolean moveSLBreakEven90 = false;

    //this is a safety feature to avoid too big position sizes due to typos
    private static final double maxPositionSize = 0.05;

    private IEngine engine;
    private IHistory history;
    private IContext context;
    private IConsole console;
    private boolean orderIsOpen;
    private double totalProfit;
    private double totalCommission;
    private String orderLabel;
    private IEngine.OrderCommand orderCmd;

    @Override
    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.history = context.getHistory();
        this.context = context;
        this.orderIsOpen = false;
        this.console = context.getConsole();
        this.totalProfit = 0;
        this.totalCommission = 0;
        this.orderLabel = "invalid";

        //subscribe instruments
        console.getOut().println("Strategy starting. Subscribing instruments...");
        subscribeInstruments();
        
        //check and setup order command
        if (isBuyOrder ^ isSellOrder) {
            if (isBuyOrder)
                orderCmd = IEngine.OrderCommand.BUY;
            else
                orderCmd = IEngine.OrderCommand.SELL;
        } else {
            console.getErr().println("Invalid order side, please check only BUY or SELL");
            return;
        }
        
        //check take profit price
        if (takeProfitPrice <= 0.0) {
            console.getErr().println("Invalid take profit price: " + takeProfitPrice);
            return;
        }

        //calc take profit pips
        double takeProfitPips;
        if (isBuyOrder) {
            takeProfitPips = Math.abs(takeProfitPrice - history.getLastTick(instrument).getAsk()) *
                    Math.pow(10, this.instrument.getPipScale());
        } else {
            takeProfitPips = Math.abs(takeProfitPrice - history.getLastTick(instrument).getBid()) *
                    Math.pow(10, this.instrument.getPipScale());
        }
        
        //calc profit pips
        double stopLossPips = takeProfitPips; //risk:reward 1:1
        
        //submit order
        String direction = orderCmd.isLong() ? "long" : "short";
        IOrder order = submitOrder(this.constantCurrencyRisk, orderCmd, stopLossPips);
        console.getInfo().println("Order " + order.getLabel()
                + " submitted. Direction: " + direction
                + " Stop loss: " + order.getStopLossPrice()
                + " Take profit: " + order.getTakeProfitPrice()
                + " Amount: " + order.getAmount());
        this.orderIsOpen = true;
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        //check if any order meets the 90% B.E. SL move requirements
        if (instrument.equals(this.instrument) && period.equals(Period.ONE_MIN) && (orderIsOpen)) {
            checkSLMoveBE();
        }
    }

    @Override
    public void onMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        if (order != null) {
            //handle only messages relative to the order managed by this instance
            if (order.getLabel().equals(orderLabel)) {
                if (message.getType() == Type.ORDER_CLOSE_OK) {
                    //update order variable on order close
                    this.orderIsOpen = false;
                    console.getInfo().println("Order " + order.getLabel()
                            + " closed. Profit: " + order.getProfitLossInAccountCurrency());
                    //update profit/loss and commission
                    this.totalProfit += order.getProfitLossInAccountCurrency();
                    this.totalCommission += order.getCommission();
                } else if (message.getType() == Type.ORDER_SUBMIT_REJECTED) {
                    //update order variable on order rejection
                    this.orderIsOpen = false;
                    console.getErr().println("Order " + order.getLabel() + " rejected.");
                } else if (message.getType() == Type.ORDER_CHANGED_REJECTED) {
                    console.getErr().println("Order " + order.getLabel() + " change rejected.");
                }
            }
        } else if ((message.getType() == Type.INSTRUMENT_STATUS)
                || (message.getType() == Type.CALENDAR)) {
            //filter out
        } else {
            context.getConsole().getOut().println("Message: " + message.toString());
        }
    }

    @Override
    public void onAccount(IAccount account) throws JFException {
    }

    @Override
    public void onStop() throws JFException {
        console.getNotif().println("Strategy stopped. Profit: " + totalProfit +
                " Commission: " + totalCommission +
                " Net Profit: " + (totalProfit - totalCommission));
    }

    private IOrder submitOrder(int currencyRisk, OrderCommand orderCmd, double stopLossPips)
            throws JFException {
        double stopLossPrice;
        ITick lastTick = history.getLastTick(instrument);
        double positionSize;
        
        //calc take profit price
        if (orderCmd == OrderCommand.BUY) {
            stopLossPrice = lastTick.getAsk() - stopLossPips * instrument.getPipValue();
        } else {
            stopLossPrice = lastTick.getBid() + stopLossPips * instrument.getPipValue();
        }

        //calc position size
        positionSize = getPositionSize(instrument, stopLossPips, currencyRisk, orderCmd);

        //create order label
        this.orderLabel = getLabel(orderCmd);

        //submit order at market
        return engine.submitOrder(orderLabel, instrument, orderCmd, positionSize, 0, 5, stopLossPrice, takeProfitPrice);
    }

    private String getLabel(OrderCommand cmd) {
        return cmd.toString() + System.currentTimeMillis();
    }

    private double getPositionSize(Instrument pair, double stopLossPips, int constantCurrencyRisk, OrderCommand orderCmd)
            throws JFException {
        //init symbols
        String accountCurrency = context.getAccount().getCurrency().getCurrencyCode();
        String primaryCurrency = pair.getPrimaryCurrency().getCurrencyCode();
        String secondaryCurrency = pair.getSecondaryCurrency().getCurrencyCode();
        
        //get exchange rate of traded pair in relation to account currency
        double accountCurrencyExchangeRate;
        String apCurrency = accountCurrency + "/" + primaryCurrency;
        Instrument i;
        
        if (primaryCurrency.equals(accountCurrency)) {
            i = pair;
        } else {
            i = Instrument.fromString(apCurrency);
        }
        
        if (i == null) { //currency not found, try inverted pair
            i = Instrument.fromInvertedString(apCurrency);
            if (orderCmd == OrderCommand.BUY)
                accountCurrencyExchangeRate = 1 / history.getLastTick(i).getAsk();
            else
                accountCurrencyExchangeRate = 1 / history.getLastTick(i).getBid();
        } else {
            if (orderCmd == OrderCommand.BUY)
                accountCurrencyExchangeRate = history.getLastTick(i).getAsk();
            else
                accountCurrencyExchangeRate = history.getLastTick(i).getBid();
        }
        
        //calc currency/pip value
        double pairExchangeRate;
        if (orderCmd == OrderCommand.BUY)
            pairExchangeRate = history.getLastTick(pair).getAsk();
        else
            pairExchangeRate = history.getLastTick(pair).getBid();
        double accountCurrencyPerPip = pair.getPipValue() / pairExchangeRate *
                                       100000;
        if (!primaryCurrency.equals(accountCurrency)) 
            accountCurrencyPerPip /= accountCurrencyExchangeRate; //convert to account pip value
        
        //calc position size
        double units = constantCurrencyRisk / stopLossPips * 100000 / accountCurrencyPerPip;
        
        //convert to standard lots
        double lots = units / 1000000;

        //check position size safety
        if (lots > maxPositionSize) {
            console.getErr().println("Position size exceeds safety check, maxPositionSize constant"
                    + " is " + maxPositionSize + " lots. But current position size is " + lots + " lots.");
            lots = 0;
        }

        return lots;
    }

    private void checkSLMoveBE() throws JFException {
        if (moveSLBreakEven90) { //is it user enabled
            double percent90Profit = this.constantCurrencyRisk * 0.90;
            IOrder o = engine.getOrder(orderLabel);
            if (o != null) {
                if (o.getProfitLossInAccountCurrency() >= percent90Profit) {
                    double openPrice = o.getOpenPrice();
                    if (o.getStopLossPrice() != openPrice) {
                        o.setStopLossPrice(openPrice); // move SL to B.E.
                        console.getOut().println("Order " + o.getLabel() + ": SL moved to B.E.");
                    }
                }
            } else {
                console.getErr().println("Order " + orderLabel + " not found");
            }
        }
    }
    
    private void subscribeInstruments() {
        //init list
        Set<Instrument> instruments = new HashSet<Instrument>();
        instruments.add(instrument);

        //init symbols
        String accountCurrency = context.getAccount().getCurrency().getCurrencyCode();
        String primaryCurrency = instrument.getPrimaryCurrency().getCurrencyCode();
        String apCurrency = accountCurrency + "/" + primaryCurrency;

        //find complementary instrument
        Instrument i;
        if (primaryCurrency.equals(accountCurrency)) {
            i = instrument;
        } else {
            i = Instrument.fromString(apCurrency);
        }
        if (i == null) { //currency not found, try inverted pair
            i = Instrument.fromInvertedString(apCurrency);
        }
        if (i != instrument) {
            instruments.add(i);
        }

        //subscribe
        context.setSubscribedInstruments(instruments, true);
    }

}

