# host_test Makefile — tests frame.c (pure C, no IDF)
#
# Usage:
#   make -C firmware/host_test test       # run unit tests (always rebuilds)
#   make -C firmware/host_test e2e        # run end-to-end test (always rebuilds)

.PHONY: test e2e clean

CFLAGS = -Wall -Wextra -Werror -std=c11 -g -O2 \
         -I../main \
         -DFRAME_TEST

FRAME_SRC = ../main/frame.c

# --- unit test ------------------------------------------------------------

test:
	$(CC) $(CFLAGS) -o test_frame $(FRAME_SRC) test_frame.c
	./test_frame
	@echo "ALL TESTS PASSED"

# --- e2e test -------------------------------------------------------------

e2e:
	$(CC) $(CFLAGS) -o e2e_test $(FRAME_SRC) e2e.c
	./e2e_test
	@echo "E2E TEST PASSED"

clean:
	rm -f test_frame e2e_test