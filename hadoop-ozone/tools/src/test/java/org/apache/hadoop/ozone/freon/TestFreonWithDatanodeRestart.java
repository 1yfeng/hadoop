/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.freon;

import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.container.common.transport.server.XceiverServerSpi;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.ContainerStateMachine;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.XceiverServerRatis;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_STALENODE_INTERVAL;

/**
 * Tests Freon with Datanode restarts.
 */
public class TestFreonWithDatanodeRestart {

  private static MiniOzoneCluster cluster;
  private static OzoneConfiguration conf;

  /**
   * Create a MiniDFSCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   */
  @BeforeClass
  public static void init() throws Exception {
    conf = new OzoneConfiguration();
    conf.setTimeDuration(OZONE_SCM_STALENODE_INTERVAL, 5, TimeUnit.SECONDS);
    cluster = MiniOzoneCluster.newBuilder(conf)
      .setHbProcessorInterval(1000)
      .setHbInterval(1000)
      .setNumDatanodes(3)
      .build();
    cluster.waitForClusterToBeReady();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @AfterClass
  public static void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testRestart() throws Exception {
    RandomKeyGenerator randomKeyGenerator =
        new RandomKeyGenerator((OzoneConfiguration) cluster.getConf());
    randomKeyGenerator.setNumOfVolumes(1);
    randomKeyGenerator.setNumOfBuckets(1);
    randomKeyGenerator.setNumOfKeys(1);
    randomKeyGenerator.setType(ReplicationType.RATIS);
    randomKeyGenerator.setFactor(ReplicationFactor.THREE);
    randomKeyGenerator.setKeySize(20971520);
    randomKeyGenerator.setValidateWrites(true);
    randomKeyGenerator.call();
    Assert.assertEquals(1, randomKeyGenerator.getNumberOfVolumesCreated());
    Assert.assertEquals(1, randomKeyGenerator.getNumberOfBucketsCreated());
    Assert.assertEquals(1, randomKeyGenerator.getNumberOfKeysAdded());
    Assert.assertEquals(0, randomKeyGenerator.getUnsuccessfulValidationCount());

    ContainerStateMachine sm = getStateMachine();
    TermIndex termIndexBeforeRestart = sm.getLastAppliedTermIndex();
    cluster.restartHddsDatanode(0);
    sm = getStateMachine();
    SimpleStateMachineStorage storage =
        (SimpleStateMachineStorage)sm.getStateMachineStorage();
    SingleFileSnapshotInfo snapshotInfo = storage.getLatestSnapshot();
    TermIndex termInSnapshot = snapshotInfo.getTermIndex();
    String expectedSnapFile =
        storage.getSnapshotFile(termIndexBeforeRestart.getTerm(),
            termIndexBeforeRestart.getIndex()).getAbsolutePath();
    Assert.assertEquals(snapshotInfo.getFile().getPath().toString(),
        expectedSnapFile);
    Assert.assertEquals(termInSnapshot, termIndexBeforeRestart);

    // After restart the term index might have progressed to apply pending
    // transactions.
    TermIndex termIndexAfterRestart = sm.getLastAppliedTermIndex();
    Assert.assertTrue(termIndexAfterRestart.getIndex() >=
        termIndexBeforeRestart.getIndex());
  }

  private ContainerStateMachine getStateMachine() {
    XceiverServerSpi server =
        cluster.getHddsDatanodes().get(0).getDatanodeStateMachine().
            getContainer().getServer(HddsProtos.ReplicationType.RATIS);
    return ((XceiverServerRatis)server).getStateMachine();
  }
}
