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
import java.math.BigDecimal;

/*
 * This tool places a market order with constant currency risk.
 * Once you define your position currency risk and stop loss pips,
 * this tool will calculate the right amount (lot size) to meet the defined currency risk.
 * Other nice features, auto take profit price calculation based on risk:reward
 * ratio, stop loss move to break even once the price reached 90% of target (TP).
 * Use at your own risk.
 */
public class ConstantRiskMarketTool implements IStrategy {

    // Configurable parameters
    @Configurable("Instrument")
    public Instrument instrument = Instrument.EURUSD;
    @Configurable("Period")
    public Period period = Period.DAILY;
    @Configurable(value = "Order side",
            description = "Long/BUY or short/SELL")
    public OrderCommand orderCmd = OrderCommand.BUY;
    @Configurable(value = "Constant risk amount",
            description = "Constant account currency risk for each trade")
    public int constantCurrencyRisk = 100;
    @Configurable(value = "Stop loss pips",
            description = "Distance of stop loss from market entry in pips")
    public double stopLossPips = 50;
    @Configurable(value = "Reward risk ratio",
            description = "Use 2 for risk:reward ratio of 1:2")
    public double rewardRiskRatio = 2;
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

    @Override
    public void onStart(IContext context) throws JFException {
        this.engine = context.getEngine();
        this.history = context.getHistory();
        this.context = context;
        this.orderIsOpen = false;
        this.console = context.getConsole();
        this.totalProfit = 0;
        this.totalCommission = 0;
        this.orderLabel = "";

        //subscribe instruments
        console.getOut().println("Strategy starting. Subscribing instruments...");
        context.setSubscribedInstruments(java.util.Collections.singleton(instrument));
        
        //check order command
        if ((orderCmd != OrderCommand.BUY)  && (orderCmd != OrderCommand.SELL)) {
            console.getErr().println("Invalid order side, please use only BUY or SELL");
            return;
        }

        //calc profit pips
        double takeProfitPips = stopLossPips; //risk:reward 1:1
        if (rewardRiskRatio != 1) { //adjust reward if needed (custom risk:reward)
            takeProfitPips *= rewardRiskRatio; //reward:risk
            if ((takeProfitPips % 0.1) != 0) {
            //round to 0.1 pip minimum requirement format, since not multiple of 0.1
            takeProfitPips = (new BigDecimal(takeProfitPips)).setScale(1, BigDecimal.ROUND_HALF_UP).doubleValue();
            }
        }

        //submit order
        String direction = orderCmd.isLong() ? "long" : "short";
        IOrder order = submitOrder(this.constantCurrencyRisk, orderCmd, stopLossPips, takeProfitPips);
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
        if (message.getType() == Type.ORDER_CLOSE_OK) {
            //update order variable on order close
            this.orderIsOpen = false;
            IOrder order = message.getOrder();
            console.getInfo().println("Order " + order.getLabel() +
                                      " closed. Profit: " + order.getProfitLossInAccountCurrency());
            //update profit/loss and commission
            this.totalProfit += order.getProfitLossInAccountCurrency();
            this.totalCommission += order.getCommission();
            
        } else if (message.getType() == Type.ORDER_SUBMIT_REJECTED) {
            //update order variable on order rejection
            this.orderIsOpen = false;
            IOrder order = message.getOrder();
            console.getErr().println("Order " + order.getLabel() + " rejected.");

        } else if (message.getType() == Type.ORDER_CHANGED_REJECTED) {
            IOrder order = message.getOrder();
            console.getErr().println("Order " + order.getLabel() + " change rejected.");

        } else if (message.getType() == Type.INSTRUMENT_STATUS) {
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

    private IOrder submitOrder(int currencyRisk, OrderCommand orderCmd, double stopLossPips, double takeProfitPips)
            throws JFException {
        double stopLossPrice, takeProfitPrice;
        ITick lastTick = history.getLastTick(instrument);
        double positionSize;
        
        //calc stop loss and take profit prices
        if (orderCmd == OrderCommand.BUY) {
            stopLossPrice = lastTick.getAsk() - stopLossPips * instrument.getPipValue();
            takeProfitPrice = lastTick.getAsk() + takeProfitPips * instrument.getPipValue();
        } else {
            stopLossPrice = lastTick.getBid() + stopLossPips * instrument.getPipValue();
            takeProfitPrice = lastTick.getBid() - takeProfitPips * instrument.getPipValue();
        }
        
        //calc position size
        positionSize = getPositionSize(instrument, stopLossPips, currencyRisk, orderCmd);
        
        //submit order at market
        return engine.submitOrder(getLabel(orderCmd), instrument, orderCmd, positionSize, 0, 5, stopLossPrice, takeProfitPrice);
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
            double percent90Profit = this.constantCurrencyRisk * 0.90 * rewardRiskRatio;
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

}
