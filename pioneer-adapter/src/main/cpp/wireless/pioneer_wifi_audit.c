/* Read-only AP interface audit; never logs credentials. */
#include <linux/netlink.h>
#include <linux/genetlink.h>
#include <linux/nl80211.h>
#include <sys/socket.h>
#include <net/if.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
static int fd;
static int req(int family,int cmd,int type,const void *data,int size,char *reply){
 char b[4096]={0};struct nlmsghdr *n=(void*)b;struct genlmsghdr *g=(void*)(n+1);
 n->nlmsg_len=NLMSG_LENGTH(GENL_HDRLEN);n->nlmsg_type=family;n->nlmsg_flags=NLM_F_REQUEST;n->nlmsg_seq=1;g->cmd=cmd;g->version=1;
 if(size){struct nlattr *a=(void*)(b+n->nlmsg_len);a->nla_type=type;a->nla_len=NLA_HDRLEN+size;memcpy((char*)a+NLA_HDRLEN,data,size);n->nlmsg_len+=NLA_ALIGN(a->nla_len);}
 struct sockaddr_nl to={.nl_family=AF_NETLINK};if(sendto(fd,b,n->nlmsg_len,0,(void*)&to,sizeof(to))<0){perror("send");return -1;}int r=recv(fd,reply,65536,0);if(r<0)perror("recv");return r;
}
int main(){fd=socket(AF_NETLINK,SOCK_RAW,NETLINK_GENERIC);struct timeval t={3,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&t,sizeof(t));struct sockaddr_nl addr={.nl_family=AF_NETLINK};if(bind(fd,(void*)&addr,sizeof(addr))){perror("bind");return 1;}
 char b[65536];int r=req(GENL_ID_CTRL,CTRL_CMD_GETFAMILY,CTRL_ATTR_FAMILY_NAME,"nl80211",8,b),family=0;
 if(r<0)return 1;struct nlmsghdr*n=(void*)b;
 for(int off=NLMSG_HDRLEN+GENL_HDRLEN;off+NLA_HDRLEN<=r;){struct nlattr*a=(void*)(b+off);if(a->nla_len<NLA_HDRLEN)break;if(a->nla_type==CTRL_ATTR_FAMILY_ID)memcpy(&family,(char*)a+NLA_HDRLEN,2);off+=NLA_ALIGN(a->nla_len);}printf("family=%d\n",family);if(!family)return 1;
 unsigned index=if_nametoindex("bcm1");printf("bcm1_ifindex=%u\n",index);r=req(family,NL80211_CMD_GET_INTERFACE,NL80211_ATTR_IFINDEX,&index,4,b);if(r<0)return 1;n=(void*)b;if(n->nlmsg_type==NLMSG_ERROR){printf("netlink_error=%d\n",((struct nlmsgerr*)NLMSG_DATA(n))->error);return 1;}
 for(int off=NLMSG_HDRLEN+GENL_HDRLEN;off+NLA_HDRLEN<=r;){struct nlattr*a=(void*)(b+off);if(a->nla_len<NLA_HDRLEN)break;unsigned v=0;if(a->nla_len>=NLA_HDRLEN+4)memcpy(&v,(char*)a+NLA_HDRLEN,4);int type=a->nla_type&NLA_TYPE_MASK;
 if(type==NL80211_ATTR_IFTYPE)printf("iftype=%u (AP=%u)\n",v,NL80211_IFTYPE_AP);if(type==NL80211_ATTR_WIPHY_FREQ)printf("frequency_mhz=%u\n",v);if(type==NL80211_ATTR_CHANNEL_WIDTH)printf("channel_width_enum=%u\n",v);if(type==NL80211_ATTR_CENTER_FREQ1)printf("center_frequency1_mhz=%u\n",v);off+=NLA_ALIGN(a->nla_len);}
 close(fd);return 0;}
