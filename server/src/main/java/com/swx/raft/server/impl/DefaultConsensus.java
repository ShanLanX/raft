package com.swx.raft.server.impl;

import com.swx.raft.common.entity.AentryParam;
import com.swx.raft.common.entity.AentryResult;
import com.swx.raft.common.entity.RvoteParam;
import com.swx.raft.common.entity.RvoteResult;
import com.swx.raft.server.Consensus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultConsensus implements Consensus {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConsensus.class);


    public RvoteResult requestVote(RvoteParam rvoteParam) {
        return null;
    }

    public AentryResult appendEntries(AentryParam param) {
        return null;
    }
}
