/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.samples.crosschain.atomicswapether;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.besu.Besu;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.CrosschainTransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.gas.StaticGasProvider;
import tech.pegasys.samples.crosschain.atomicswapether.soliditywrappers.AtomicSwapReceiver;
import tech.pegasys.samples.crosschain.atomicswapether.soliditywrappers.AtomicSwapRegistration;
import tech.pegasys.samples.crosschain.atomicswapether.soliditywrappers.AtomicSwapSender;
import tech.pegasys.samples.crosschain.atomicswapether.utils.AbstractPropertiesFile;
import tech.pegasys.samples.crosschain.atomicswapether.utils.KeyPairGen;

import java.math.BigInteger;

/**
 * Act as the entity which accepts offers Ether on sidechain 2 and gives Ether on sidechain 1.
 */
public class EntityAcceptingOffer {
    private static final Logger LOG = LogManager.getLogger(EntityAcceptingOffer.class);

    private Credentials credentials;
    private CrosschainTransactionManager tmSc1;
    private CrosschainTransactionManager tmSc2;
    private Besu web3jSc1;
    private Besu web3jSc2;
    private BigInteger sc1Id;
    private BigInteger sc2Id;

    BigInteger exchangeRate;

    // A gas provider which indicates no gas is charged for transactions.
    private ContractGasProvider freeGasProvider =  new StaticGasProvider(BigInteger.ZERO, DefaultGasProvider.GAS_LIMIT);


    // Smart contract addresses and objected.of contracts.
    private String senderContractAddress = null;
    private String receiverContractAddress = null;
    private AtomicSwapSender senderContract;
    private AtomicSwapReceiver receiverContract;


    public EntityAcceptingOffer(final Besu web3jSc1, final BigInteger sc1Id, final Besu web3jSc2, final BigInteger sc2Id,
                                final int retry, final int pollingInterval) {
        loadStoreProperties();
        this.web3jSc1 = web3jSc1;
        this.web3jSc2 = web3jSc2;
        this.tmSc1 = new CrosschainTransactionManager(this.web3jSc1, this.credentials, sc1Id.longValue(), retry, pollingInterval);
        this.tmSc2 = new CrosschainTransactionManager(this.web3jSc2, this.credentials, sc2Id.longValue(), retry, pollingInterval);
        this.sc1Id = sc1Id;
        this.sc2Id = sc2Id;
    }



    public void showOffers(String registrationContractAddress) throws Exception {
        LOG.info("Showing Offers registered with Registration Contract");
        AtomicSwapRegistration registrationContract = AtomicSwapRegistration.load(registrationContractAddress, this.web3jSc1, this.tmSc1, this.freeGasProvider);
        BigInteger size = registrationContract.getOfferAddressesSize(this.sc2Id).send();
        LOG.info("Total number of sender contracts registered for sending Ether to sidechain {} is {}", this.sc2Id, size);

        int sizeInt = size.intValue();
        if (sizeInt == 0) {
            LOG.info("No send contracts registered for sending Ether to");
            return;
        }

        BigInteger exchangeRateOffered = null;
        String sendContractAddress = null;
        for (int i = 0; i < sizeInt; i++) {
            exchangeRateOffered = registrationContract.getOfferExchangeRate(this.sc2Id, BigInteger.valueOf(i)).send();
            sendContractAddress = registrationContract.getOfferSenderContract(this.sc2Id, BigInteger.valueOf(i)).send();
            LOG.info(" Exchange Rate {} offered at Send Contract {}", exchangeRateOffered.toString(16), sendContractAddress);
        }
    }


    public void prepareForExchange(String registrationContractAddress, int offerNumber) throws Exception {
        LOG.info("Set-up and load contracts");
        AtomicSwapRegistration registrationContract = AtomicSwapRegistration.load(registrationContractAddress, this.web3jSc1, this.tmSc1, this.freeGasProvider);
        BigInteger size = registrationContract.getOfferAddressesSize(this.sc2Id).send();
        int sizeInt = size.intValue();
        if (sizeInt <= offerNumber) {
            LOG.error("No offer numbber {} available");
            return;
        }

        BigInteger exchangeRateOffered = registrationContract.getOfferExchangeRate(this.sc2Id, BigInteger.valueOf(offerNumber)).send();
        String sendContractAddress = registrationContract.getOfferSenderContract(this.sc2Id, BigInteger.valueOf(offerNumber)).send();
        LOG.info(" Exchange Rate {} offered at Send Contract {}", exchangeRateOffered.toString(16), sendContractAddress);

        this.senderContract = AtomicSwapSender.load(sendContractAddress, this.web3jSc1, this.tmSc1, this.freeGasProvider);
        this.senderContractAddress = this.senderContract.getContractAddress();
        this.receiverContractAddress = this.senderContract.receiverContract().send();
        this.receiverContract = AtomicSwapReceiver.load(this.receiverContractAddress, this.web3jSc2, this.tmSc2, this.freeGasProvider);
        this.exchangeRate = exchangeRateOffered;
    }



    public void swapEther(BigInteger amountInWei) throws Exception {
        LOG.info("Running Core Part of Sample Code");

        CallSimulator sim = new CallSimulator(this.exchangeRate);
        LOG.info("   Scaled exchange rate is: 0x{}", this.exchangeRate.toString(16));

        LOG.info("  Executing call simulator to determine parameter values and expected results");
        BigInteger receiverBalanceInWei = getBalance(this.web3jSc2, this.receiverContractAddress);
        BigInteger senderBalanceInWei = getBalance(this.web3jSc1, this.senderContractAddress);
        BigInteger accepterBalanceInWei = getBalance(this.web3jSc2, this.credentials.getAddress());
        sim.setValues(receiverBalanceInWei, accepterBalanceInWei, senderBalanceInWei);
        sim.exchange(amountInWei);
        if (sim.atomicSwapSenderError) {
            LOG.info("***Simulator detected error while processing request: SenderError");
            return;
        }
        if (sim.atomicSwapReceiverError) {
            LOG.info("***Simulator detected error while processing request: ReceiverError");
            return;
        }

        LOG.info("   Simulator says: Receive amount is: {} Wei", sim.atomicSwapReceiver_Exchange_amount);
        LOG.info("   Simulator says: Receive contract balance will be: {} Wei", sim.receiverBalanceInWei);
        LOG.info("   Simulator says: Send contract balance will be: {} Wei", sim.senderBalanceInWei);
        LOG.info("   Simulator says: Accept account balance will be: {} Wei", sim.accepterBalanceInWei);

        LOG.info("  Constructing Nested Crosschain Transaction");
        byte[] subordinateTrans = this.receiverContract.exchange_AsSignedCrosschainSubordinateTransaction(sim.atomicSwapReceiver_Exchange_amount, null);

        // Call to contract 1
        byte[][] subordinateTransactionsAndViews = new byte[][]{subordinateTrans};
        LOG.info("  Executing Crosschain Transaction");
        TransactionReceipt transactionReceipt = this.senderContract.exchange_AsCrosschainTransaction(subordinateTransactionsAndViews, amountInWei).send();
        LOG.info("   Transaction Receipt: {}", transactionReceipt.toString());
        if (!transactionReceipt.isStatusOK()) {
            throw new Error(transactionReceipt.getStatus());
        }

        // TODO should check to see if contracts unlocked before fetching values.
        Thread.sleep(5000);
    }


    public String accountAddress() {
        return this.credentials.getAddress();
    }


    private BigInteger getBalance(Besu besu, String address) throws Exception {
        EthGetBalance ethGetBalance=
            besu.ethGetBalance(address, DefaultBlockParameterName.LATEST).sendAsync().get();
        return ethGetBalance.getBalance();
    }


    private void loadStoreProperties() {
        EntityOfferingProperties props = new EntityOfferingProperties();
        if (props.propertiesFileExists()) {
            props.load();
        }
        else {
            // Generate a key and store it in the format required for Credentials.
            props.privateKey = new KeyPairGen().generateKeyPairGetPrivateKey();
            props.store();
        }
        this.credentials = Credentials.create(props.privateKey);
    }




    static class EntityOfferingProperties extends AbstractPropertiesFile {
        private static final String PROP_PRIV_KEY = "privateKey";
        String privateKey;

        EntityOfferingProperties() {
            super("accepting");
        }

        void load() {
            loadProperties();
            this.privateKey = this.properties.getProperty(PROP_PRIV_KEY);
        }

        void store() {
            this.properties.setProperty(PROP_PRIV_KEY, this.privateKey);
            storeProperties();
        }
    }
}