package com.longvin.trading.accounts;

/**
 * Enum representing the type of account.
 */
public enum AccountType {
    /**
     * Primary account - the main account that receives orders from DAS Trader.
     */
    PRIMARY,
    
    /**
     * Shadow account - accounts that receive replicated orders from the primary account.
     */
    SHADOW
}

