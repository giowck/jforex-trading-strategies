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
 * Once you define your position currency risk, stop loss price and take profit targets
 * this tool will calculate the right amount (lot size) to meet the defined currency risk.
 * This includes a scale out mechanism, where you can define target 1 (T1) and target 2 (T2).
 * It is also possible to specify the break even trigger price, which is the price
 * where the stop loss (SL) is moved to break even (B.E.)
 * Use at your own risk.
 */
public class ConstRiskMarketScaleOut implements IStrategy {

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
    @Configurable(value = "Stop loss price",
            description = "Price of stop loss placement")
    public double stopLossPrice = 0;
    @Configurable(value = "Target 1 price",
            description = "Price of take profit level for target 1")
    public double target1Price = 0;
    @Configurable(value = "Target 2 price",
            description = "Price of take profit level for target 2, if 0 full position is closed at T1")
    public double target2Price = 0;
    @Configurable(value = "Break even trigger price",
            description = "Move stop loss to break even once this price is hit, 0 means not active")
    public double breakEvenTriggerPrice = 0;

    //this is a safety feature to avoid too big position sizes due to typos
    private static final double maxPositionSize = 0.05;

    private IEngine engine;
    private IHistory history;
    private IContext context;
    private IConsole console;
    private boolean order1IsOpen;
    private boolean order2IsOpen;
    private double totalProfit;
    private double totalCommission;
    private String order1Label;
    private String order2Label;
    private boolean scaleOutActive;
    private boolean moveSLToBreakEvenActive;
    private IEngine.OrderCommand orderCmd;

    @Override
    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.history = context.getHistory();
        this.context = context;
        this.order1IsOpen = false;
        this.order2IsOpen = false;
        this.console = context.getConsole();
        this.totalProfit = 0;
        this.totalCommission = 0;
        this.order1Label = "";
        this.order2Label = "";

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
        
        //check stop loss price
        if (stopLossPrice <= 0.0) {
            console.getErr().println("Invalid stop loss price: " + stopLossPrice);
            return;
        }
        //check target 1 price
        if (target1Price <= 0.0) {
            console.getErr().println("Invalid target 1 price: " + target1Price);
            return;
        }
        
        //check if scale out is active (if T2 is 0, no scale out, exit full position on T1)
        this.scaleOutActive = (target2Price > 0.0);
        
        //if scale out active halve currency risk, because of position plitting on 2 orders
        if (scaleOutActive) {
            constantCurrencyRisk /= 2;
        }
        
        //check if break even prive trigger is active (if 0, don't move SL to BE)
        this.moveSLToBreakEvenActive = (breakEvenTriggerPrice > 0.0);

        //submit order
        String direction = orderCmd.isLong() ? "long" : "short";
        IOrder order1 = submitOrder(this.constantCurrencyRisk, orderCmd, stopLossPrice, target1Price);
        console.getInfo().println("Order 1" + order1.getLabel()
                + " submitted. Direction: " + direction
                + " Stop loss: " + order1.getStopLossPrice()
                + " Take profit: " + order1.getTakeProfitPrice()
                + " Amount: " + order1.getAmount());
        order1Label = order1.getLabel();
        this.order1IsOpen = true;
        
        if (scaleOutActive) { //open 2nd order
            IOrder order2 = submitOrder(this.constantCurrencyRisk, orderCmd, stopLossPrice, target2Price);
            console.getInfo().println("Order 2" + order2.getLabel()
                    + " submitted. Direction: " + direction
                    + " Stop loss: " + order2.getStopLossPrice()
                    + " Take profit: " + order2.getTakeProfitPrice()
                    + " Amount: " + order2.getAmount());
            order2Label = order2.getLabel();
            this.order2IsOpen = true;
        }
    }

    @Override
    public void onTick(Instrument instrument, ITick tick) throws JFException {
    }

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        //check if any order meets the B.E. SL move requirements
        if (instrument.equals(this.instrument) && period.equals(Period.ONE_MIN) && (order1IsOpen || order2IsOpen)) {
            checkSLMoveBE();
        }
    }

        @Override
    public void onMessage(IMessage message) throws JFException {
        if (message.getType() == Type.ORDER_CLOSE_OK) {
            //update order variables on order close
            IOrder order = message.getOrder();

            if (order.getLabel() == order1Label) { //check order 1
                this.order1IsOpen = false;
                console.getInfo().println("Order 1 " + order.getLabel()
                        + " closed. Profit: " + order.getProfitLossInAccountCurrency());
                //update profit/loss and commission
                this.totalProfit += order.getProfitLossInAccountCurrency();
                this.totalCommission += order.getCommission();
            } else if (order.getLabel() == order2Label) { //check order 2
                this.order2IsOpen = false;
                console.getInfo().println("Order 2 " + order.getLabel()
                        + " closed. Profit: " + order.getProfitLossInAccountCurrency());
                //update profit/loss and commission
                this.totalProfit += order.getProfitLossInAccountCurrency();
                this.totalCommission += order.getCommission();
            } else  {
                //nothing
            }
            
        } else if (message.getType() == Type.ORDER_SUBMIT_REJECTED) {
            //update order variables on order rejection
            IOrder order = message.getOrder();
            
            if (order.getLabel() == order1Label) { //check order 1
                this.order1IsOpen = false;
                console.getErr().println("Order 1 " + order.getLabel() + " rejected.");
            } else if (order.getLabel() == order2Label) { //check order 2
                this.order2IsOpen = false;
                console.getErr().println("Order 2 " + order.getLabel() + " rejected.");
            } else  {
                //nothing
            }

        } else if (message.getType() == Type.ORDER_CHANGED_REJECTED) {
            IOrder order = message.getOrder();
            console.getErr().println("Order " + order.getLabel() + " change rejected.");

        } else if ((message.getType() == Type.INSTRUMENT_STATUS)
                || (message.getType() == Type.CALENDAR)) {
            //filter out
            return;
        }
        context.getConsole().getOut().println("Message: " + message.toString());
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

    private IOrder submitOrder(int currencyRisk, OrderCommand orderCmd, double stopLossPrice, double takeProfitPrice)
            throws JFException {
        double positionSize;
        
        //calc position size
        positionSize = getPositionSize(instrument, stopLossPrice, currencyRisk, orderCmd);
        
        //submit order at market
        return engine.submitOrder(getLabel(orderCmd), instrument, orderCmd, positionSize, 0, 5, stopLossPrice, takeProfitPrice);
    }
    
    private String getLabel(OrderCommand cmd) {
        String orderNum = (order1IsOpen) ? "ORDER2" : "ORDER1";
        return cmd.toString() + orderNum + System.currentTimeMillis();
    }

    private double getPositionSize(Instrument pair, double stopLossPrice, int constantCurrencyRisk, OrderCommand orderCmd)
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
        
        //calc stop loss pips
        double stopLossPips;
        if (orderCmd == OrderCommand.BUY) {
            stopLossPips = Math.abs(stopLossPrice - history.getLastTick(instrument).getAsk()) *
                    Math.pow(10, this.instrument.getPipScale());
        } else {
            stopLossPips = Math.abs(stopLossPrice - history.getLastTick(instrument).getBid()) *
                    Math.pow(10, this.instrument.getPipScale());
        }
        
        //calc position size
        double units = constantCurrencyRisk / stopLossPips * 100000 / accountCurrencyPerPip;
        
        //convert to standard lots
        double lots = units / 1000000;

        //check position size safety
        double positionSizeLimit;
        positionSizeLimit = (!scaleOutActive) ? maxPositionSize : (maxPositionSize / 2);
        if (lots > positionSizeLimit) {
            console.getErr().println("Position size exceeds safety check, maxPositionSize constant"
                    + " is " + positionSizeLimit + " lots. But current position size is " + lots + " lots.");
            lots = 0;
        }

        return lots;
    }

    private void checkSLMoveBE() throws JFException {
        if (moveSLToBreakEvenActive) { //is it user enabled
            //get last tick price
            ITick lastTick = history.getLastTick(instrument);
            double currentTickPrice = (orderCmd == OrderCommand.BUY) ? lastTick.getAsk() : lastTick.getBid();
            boolean breakEvenTriggerReached = false;
            
            //check T1 hit
            if ((isBuyOrder) && (currentTickPrice >= breakEvenTriggerPrice)) {
                breakEvenTriggerReached = true;
            } else if ((isSellOrder) && (currentTickPrice <= breakEvenTriggerPrice)) {
                breakEvenTriggerReached = true;
            }

            //check order 1
            if (order1IsOpen) {
                IOrder o1 = engine.getOrder(order1Label);
                if (o1 != null) {
                    double openPrice = o1.getOpenPrice();
                    if (o1.getStopLossPrice() != openPrice) {
                        if (breakEvenTriggerReached) {
                            o1.setStopLossPrice(openPrice); // move SL to B.E.
                            console.getOut().println("Order 1 " + o1.getLabel() + ": SL moved to B.E.");
                        }
                    }
                } else {
                    console.getErr().println("Order 1 " + order1Label + " not found");
                }
            }

            //check order 2
            if (scaleOutActive && order2IsOpen) {
                IOrder o2 = engine.getOrder(order2Label);
                if (o2 != null) {
                    double openPrice = o2.getOpenPrice();
                    if (o2.getStopLossPrice() != openPrice) {
                        if (breakEvenTriggerReached) {
                            o2.setStopLossPrice(openPrice); // move SL to B.E.
                            console.getOut().println("Order 2 " + o2.getLabel() + ": SL moved to B.E.");
                        }
                    }
                } else {
                    console.getErr().println("Order 2 " + order2Label + " not found");
                }
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
