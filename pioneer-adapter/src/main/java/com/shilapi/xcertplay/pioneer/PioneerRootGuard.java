package com.shilapi.xcertplay.pioneer;

/** Revalidate ownership and cancellation before a privileged operation. */
interface PioneerRootGuard { void check() throws Exception; }
