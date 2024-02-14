package com.swx.raft.common.rpc;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
@Data

public class Response<T> implements Serializable {
    private T result;

    public Response(T result) {
        this.result = result;
    }

    private Response(Builder builder) {
        setResult((T) builder.result);
    }

    public static Response<String> ok() {
        return new Response<>("ok");
    }

    public static Response<String> fail() {
        return new Response<>("fail");
    }
    public static final class Builder {

        private Object result;

        private Builder() {
        }

        public Builder result(Object val) {
            result = val;
            return this;
        }

        public Response<?> build() {
            return new Response(this);
        }
    }


    @Override
    public String toString() {
        return "Response{" +
                "result=" + result +
                '}';
    }
}
